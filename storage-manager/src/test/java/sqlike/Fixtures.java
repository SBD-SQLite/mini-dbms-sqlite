package sqlike;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import sqlike.block.BlockManager;
import sqlike.buffer.BufferPoolManager;
import sqlike.catalog.CatalogManager;
import sqlike.contract.IWalDurability;
import sqlike.index.IndexManager;
import sqlike.record.RecordManager;
import sqlike.record.RecordSerializer;
import sqlike.replacement_durability.LRUReplacementPolicy;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import sqlike.statistics.StatisticsManager;
import sqlike.storage.StorageErrorCode;
import sqlike.storage.StorageException;
import sqlike.storage.StorageManager;

/** Shared helpers for the Storage Manager unit tests. */
final class Fixtures {
    private Fixtures() { }

    static Value i(int v) { return new Value(DataType.INTEGER, Optional.of(v), Optional.empty(), Optional.empty()); }
    static Value f(double v) { return new Value(DataType.FLOAT, Optional.empty(), Optional.of(v), Optional.empty()); }
    static Value vc(String v) { return new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of(v)); }
    static Value ch(String v) { return new Value(DataType.CHAR, Optional.empty(), Optional.empty(), Optional.of(v)); }

    static ColumnSchema col(String name, DataType type) { return new ColumnSchema(name, type, Optional.empty()); }
    static ColumnSchema col(String name, DataType type, int len) { return new ColumnSchema(name, type, Optional.of(len)); }

    /** items(id INTEGER PK, name VARCHAR(40), price FLOAT, code CHAR(4)). */
    static TableSchema items(String tableId, String name) {
        return new TableSchema(tableId, name, List.of(
                col("id", DataType.INTEGER), col("name", DataType.VARCHAR, 40),
                col("price", DataType.FLOAT), col("code", DataType.CHAR, 4)), List.of("id"), List.of());
    }

    static List<Value> item(int id, String name, double price, String code) {
        return List.of(i(id), vc(name), f(price), ch(code));
    }

    /** Fully wired storage stack in a directory, as built by StorageDriver. */
    static final class Db implements AutoCloseable {
        final RecordingWal wal = new RecordingWal();
        final AtomicLong lsn = new AtomicLong(1000);
        final BlockManager blocks;
        final BufferPoolManager buffer;
        final CatalogManager catalog;
        final RecordSerializer serializer = new RecordSerializer();
        final RecordManager records;
        final IndexManager indexes;
        final StatisticsManager stats;
        final StorageManager sm;

        Db(Path dir) { this(dir, BlockManager.DEFAULT_PAGE_SIZE, 16); }

        Db(Path dir, int pageSize, int poolCapacity) {
            catalog = new CatalogManager(dir.resolve("catalog.bin"));
            catalog.load();
            blocks = new BlockManager(dir, pageSize);
            buffer = new BufferPoolManager(blocks, wal, new LRUReplacementPolicy(), poolCapacity);
            records = new RecordManager(buffer, serializer, catalog);
            indexes = new IndexManager(catalog, records, buffer, serializer, () -> lsn.incrementAndGet());
            stats = new StatisticsManager(catalog, blocks);
            sm = new StorageManager(records, catalog, stats, indexes, buffer);
            sm.setBlockManager(blocks);
            indexes.loadIndexes();
        }

        long next() { return lsn.incrementAndGet(); }

        RID insert(String table, List<Value> values) {
            return sm.insert(sm.reserveInsert(table, values), next());
        }

        @Override public void close() { sm.close(); }
    }

    static final class RecordingWal implements IWalDurability {
        final List<Long> requested = new ArrayList<>();
        boolean reject;

        @Override public void ensureDurable(long lsn) {
            requested.add(lsn);
            if (reject) throw new StorageException(StorageErrorCode.WAL_FAILURE, "simulated WAL failure");
        }

        long max() { return requested.stream().mapToLong(Long::longValue).max().orElse(0); }
    }

    static List<Record> scan(StorageManager sm, String table) {
        List<Record> out = new ArrayList<>();
        try (RecordCursor c = sm.scan(table)) {
            while (c.hasNext()) out.add(c.next());
        }
        return out;
    }

    static StorageErrorCode codeOf(Runnable action) {
        try {
            action.run();
        } catch (StorageException e) {
            return e.getCode();
        }
        return null;
    }
}
