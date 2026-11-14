package sqlike.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sqlike.block.BlockManager;
import sqlike.buffer.BufferPoolManager;
import sqlike.catalog.CatalogManager;
import sqlike.contract.IStorageManager;
import sqlike.index.IndexManager;
import sqlike.record.RecordManager;
import sqlike.record.RecordSerializer;
import sqlike.replacement_durability.IWalDurability;
import sqlike.replacement_durability.LRUReplacementPolicy;
import sqlike.shared.DataType;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexOperator;
import sqlike.shared.IndexType;
import sqlike.shared.InsertReservation;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import sqlike.statistics.StatisticsManager;
import sqlike.utils.SampleDatabaseLoader;

public final class StorageDriver {
    private static final IndexMetadata CATEGORY_INDEX =
            new IndexMetadata("products_by_category", "products", "category_id", IndexType.HASH);
    private static final IndexMetadata PRODUCT_INDEX =
            new IndexMetadata("products_by_id", "products", "product_id", IndexType.BPLUS_TREE);

    private final Map<String, List<List<Value>>> sampleRows = SampleDatabaseLoader.sampleRows();
    private final TestWal wal = new TestWal();
    // Start after the LSNs assigned by the sample loader.
    private long lsn = sampleRows.values().stream().mapToLong(List::size).sum();
    private int passed;
    private int failed;

    public static void main(String[] args) {
        StorageDriver driver = new StorageDriver();
        driver.runAllTests();
        System.out.printf("Storage driver: %d passed, %d failed%n", driver.passed, driver.failed);
        if (driver.failed > 0) System.exit(1);
    }

    private void runAllTests() {
        try {
            Path directory = Files.createTempDirectory("sqlike-storage-driver-");
            try {
                try (TestDatabase db = new TestDatabase(directory)) {
                    if (!run("Load sample database", db.loader::loadAtStartup)) return;
                    run("Catalog, statistics, and sample scans", () -> testCatalog(db.storage));
                    run("CRUD and insert reservations", () -> testCrud(db.storage));
                    run("Hash equality and B+Tree range lookups", () -> testIndexes(db.storage));
                    run("Buffer reuse, record restore, and WAL before flush", () -> testWal(db));
                }
                try (TestDatabase db = new TestDatabase(directory)) {
                    run("Reopen data and catalog, rebuild indexes", () -> testRestart(db.storage));
                }
            } finally {
                List<Path> paths;
                try (var files = Files.walk(directory)) {
                    paths = files.sorted(Comparator.reverseOrder()).toList();
                }
                for (Path path : paths) Files.delete(path);
            }
        } catch (IOException | RuntimeException e) {
            fail("Open, close, or clean up test database", e);
        }
    }

    private void testCatalog(IStorageManager storage) {
        check(new HashSet<>(storage.listTables()).equals(sampleRows.keySet()), "Unexpected table list");
        for (TableSchema schema : SampleDatabaseLoader.sampleSchemas()) {
            String table = schema.tableName();
            check(storage.getSchema(table.toUpperCase(Locale.ROOT)).equals(schema), "Schema mismatch for " + table);

            List<Record> records = scan(storage, table);
            check(records.stream().map(Record::values).toList().equals(sampleRows.get(table)),
                    "Sample values or scan order differ for " + table);
            Set<RID> rids = new HashSet<>();
            for (Record record : records) {
                check(record.rid().pageId().tableId().equals(schema.tableId()) && rids.add(record.rid()),
                        "Invalid or duplicate RID in " + table);
            }

            var stats = storage.getStatistics(table);
            check(stats.recordCount() == records.size() && stats.blockCount() > 0 && stats.blockingFactor() > 0,
                    "Invalid table statistics for " + table);
        }
    }

    private void testCrud(IStorageManager storage) {
        String table = "categories";
        long initialCount = storage.getStatistics(table).recordCount();
        List<Value> values = List.of(integer(1000), text(DataType.VARCHAR, "Driver category"), text(DataType.CHAR, "TEST"));

        InsertReservation cancelled = storage.reserveInsert(table, values);
        check(storage.fetch(table, cancelled.rid()).isEmpty(), "Reservation was visible before insert");
        storage.releaseReservation(cancelled);
        expectError(StorageErrorCode.INVALID_RESERVATION, () -> storage.insert(cancelled, ++lsn));

        InsertReservation reservation = storage.reserveInsert(table, values);
        RID rid = storage.insert(reservation, ++lsn);
        check(rid.equals(reservation.rid()), "Insert changed the reserved RID");
        check(storage.fetch(table, rid).equals(Optional.of(new Record(rid, values))), "Inserted record differs");
        check(storage.getStatistics(table).recordCount() == initialCount + 1, "Statistics missed insert");

        List<Value> updated = List.of(values.get(0), text(DataType.VARCHAR, "Updated driver category"), values.get(2));
        storage.update(table, rid, updated, ++lsn);
        expectError(StorageErrorCode.INVALID_VALUE, () -> storage.update(table, rid, List.of(integer(1)), ++lsn));
        check(storage.fetch(table, rid).equals(Optional.of(new Record(rid, updated))), "Updated record differs");

        storage.delete(table, rid, ++lsn);
        check(storage.fetch(table, rid).isEmpty(), "Deleted record is still fetchable");
        check(scan(storage, table).stream().noneMatch(record -> record.rid().equals(rid)), "Scan returned deleted RID");
        check(storage.getStatistics(table).recordCount() == initialCount, "Statistics missed delete");
    }

    private void testIndexes(IStorageManager storage) {
        storage.createIndex(CATEGORY_INDEX);
        storage.createIndex(PRODUCT_INDEX);
        expectError(StorageErrorCode.INVALID_VALUE, () -> storage.createIndex(
                new IndexMetadata("invalid_column", "products", "missing", IndexType.HASH)));
        checkIndexes(storage);
    }

    private void checkIndexes(IStorageManager storage) {
        check(new HashSet<>(storage.getIndexes("products")).equals(Set.of(CATEGORY_INDEX, PRODUCT_INDEX)),
                "Unexpected index metadata");
        List<Record> products = scan(storage, "products");
        IndexCondition equality = new IndexCondition(IndexOperator.EQ, integer(1), Optional.empty(), true, true);
        List<RID> expected = products.stream().filter(row -> row.values().get(2).equals(integer(1))).map(Record::rid).toList();
        checkLookup(storage, CATEGORY_INDEX, equality, expected);

        IndexCondition range = new IndexCondition(IndexOperator.BETWEEN, integer(10), Optional.of(integer(15)), true, false);
        checkLookup(storage, PRODUCT_INDEX, range, products.stream()
                .filter(row -> {
                    int id = row.values().getFirst().integerValue().orElseThrow();
                    return id >= 10 && id < 15;
                }).map(Record::rid).toList());
        checkLookup(storage, CATEGORY_INDEX,
                new IndexCondition(IndexOperator.EQ, integer(1000), Optional.empty(), true, true), List.of());
    }

    private static void checkLookup(IStorageManager storage, IndexMetadata index, IndexCondition condition, List<RID> expected) {
        List<RID> actual = storage.lookupIndex(index.indexName(), condition);
        check(actual.size() == expected.size() && new HashSet<>(actual).equals(new HashSet<>(expected)),
                "Lookup differs from scan for " + index.indexName());
    }

    private void testWal(TestDatabase db) {
        IStorageManager storage = db.storage;
        Record customer = firstRecord(storage, "customers");
        check(db.buffer.getEvictionCount() > 0, "Sample workload did not exercise buffer eviction");
        storage.flushAll();
        storage.fetch("customers", customer.rid());
        long previousHits = db.buffer.getHitCount();
        check(storage.fetch("customers", customer.rid()).equals(Optional.of(customer))
                && db.buffer.getHitCount() > previousHits, "Repeated fetch did not reuse the buffer");

        byte[] before = db.blocks.readPage(customer.rid().pageId()).getData();
        long restoreLsn = ++lsn;
        storage.restoreRecord("customers", customer.rid(), new RecordImage(true, restoredCustomer()), restoreLsn);
        wal.rejectFlush = true;
        try {
            expectError(StorageErrorCode.WAL_FAILURE, storage::flushAll);
            check(wal.requestedLsn == restoreLsn, "Flush requested the wrong WAL boundary");
            check(Arrays.equals(before, db.blocks.readPage(customer.rid().pageId()).getData()),
                    "Dirty page reached disk before WAL was durable");
        } finally {
            wal.rejectFlush = false;
        }
        wal.requestedLsn = 0;
        storage.flushAll();
        check(wal.requestedLsn == restoreLsn, "Retry did not request WAL durability for the dirty page");
        check(!Arrays.equals(before, db.blocks.readPage(customer.rid().pageId()).getData()),
                "Successful retry did not write the dirty page");
    }

    private void testRestart(IStorageManager storage) {
        check(storage.getSchema("customers").equals(SampleDatabaseLoader.sampleSchemas().stream()
                .filter(schema -> schema.tableName().equals("customers")).findFirst().orElseThrow()), "Catalog did not reload");
        List<List<Value>> expected = new ArrayList<>(sampleRows.get("customers"));
        expected.set(0, restoredCustomer());
        check(scan(storage, "customers").stream().map(Record::values).toList().equals(expected),
                "Restored record or unchanged sample rows did not survive reopen");
        check(scan(storage, "categories").stream().map(Record::values).toList().equals(sampleRows.get("categories")),
                "CRUD changed the original category rows");
        checkIndexes(storage);
    }

    private List<Value> restoredCustomer() {
        List<Value> values = new ArrayList<>(sampleRows.get("customers").getFirst());
        values.set(2, text(DataType.VARCHAR, "Driver City"));
        return values;
    }

    private static Record firstRecord(IStorageManager storage, String table) {
        try (RecordCursor cursor = storage.scan(table)) {
            return cursor.next();
        }
    }

    private static List<Record> scan(IStorageManager storage, String table) {
        List<Record> rows = new ArrayList<>();
        try (RecordCursor cursor = storage.scan(table)) {
            while (cursor.hasNext()) rows.add(cursor.next());
        }
        return rows;
    }

    private boolean run(String scenario, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + scenario);
            return true;
        } catch (RuntimeException e) {
            fail(scenario, e);
            return false;
        }
    }

    private void fail(String scenario, Exception failure) {
        failed++;
        System.out.println("[FAIL] " + scenario + " - " + failure);
    }

    private static void expectError(StorageErrorCode code, Runnable operation) {
        try {
            operation.run();
        } catch (StorageException e) {
            if (e.getCode() == code) return;
            throw new IllegalStateException("Expected " + code + ", got " + e.getCode(), e);
        }
        throw new IllegalStateException("Expected " + code + ", operation succeeded");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static Value integer(int value) {
        return new Value(DataType.INTEGER, Optional.of(value), Optional.empty(), Optional.empty());
    }

    private static Value text(DataType type, String value) {
        return new Value(type, Optional.empty(), Optional.empty(), Optional.of(value));
    }

    private final class TestDatabase implements AutoCloseable {
        private final BlockManager blocks;
        private final BufferPoolManager buffer;
        private final IStorageManager storage;
        private final SampleDatabaseLoader loader;

        private TestDatabase(Path directory) {
            CatalogManager catalog = new CatalogManager(directory.resolve("catalog.bin"));
            catalog.load();
            blocks = new BlockManager(directory);
            buffer = new BufferPoolManager(blocks, wal, new LRUReplacementPolicy(), 16);
            RecordSerializer serializer = new RecordSerializer();
            RecordManager records = new RecordManager(buffer, serializer, catalog);
            IndexManager indexes = new IndexManager(catalog, records, buffer, serializer, () -> ++lsn);
            StorageManager manager = new StorageManager(records, catalog, new StatisticsManager(catalog, blocks), indexes, buffer);
            storage = manager;
            loader = new SampleDatabaseLoader(manager, catalog);
            try {
                indexes.loadIndexes();
            } catch (RuntimeException e) {
                try {
                    blocks.close();
                } catch (RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        }

        @Override
        public void close() {
            storage.close();
        }
    }

    private static final class TestWal implements IWalDurability {
        private boolean rejectFlush;
        private long requestedLsn;

        @Override
        public void ensureDurable(long lsn) {
            requestedLsn = lsn;
            if (rejectFlush) throw new StorageException(StorageErrorCode.WAL_FAILURE, "Simulated WAL failure");
        }
    }
}
