package sqlike.storage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import sqlike.block.BlockManager;
import sqlike.shared.DropBehavior;
import sqlike.shared.ForeignKeySpec;
import sqlike.contract.IStorageManager;
import sqlike.record.RecordManager;
import sqlike.catalog.CatalogManager;
import sqlike.statistics.StatisticsManager;
import sqlike.index.IndexManager;
import sqlike.buffer.BufferPoolManager;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexStatistics;
import sqlike.shared.InsertReservation;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.TableSchema;
import sqlike.shared.TableStatistics;
import sqlike.shared.Value;

public final class StorageManager implements IStorageManager {
    private final RecordManager records;
    private final CatalogManager catalog;
    private final StatisticsManager statistics;
    private final IndexManager indexes;
    private final BufferPoolManager buffer;
    private BlockManager blocks;
    private static final Pattern FILE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    public StorageManager(
        RecordManager records,
        CatalogManager catalog,
        StatisticsManager statistics,
        IndexManager indexes,
        BufferPoolManager buffer
    ) {
        this.records = records;
        this.catalog = catalog;
        this.statistics = statistics;
        this.indexes = indexes;
        this.buffer = buffer;
        if (statistics != null && records != null) {
            statistics.setRecords(records);
        }
    }

    @Override
    public RecordCursor scan(String table) throws StorageException {
        return null;
    }

    @Override
    public Optional<Record> fetch(String table, RID rid) throws StorageException {
        return Optional.empty();
    }

    @Override
    public InsertReservation reserveInsert(String table, List<Value> values) throws StorageException {
        return null;
    }

    @Override
    public RID insert(InsertReservation reservation, long lsn) throws StorageException {
        RID rid = records.insert(reservation, lsn);
        invalidateStatistics(reservation.tableName());
        return rid;
    }

    @Override
    public void update(String table, RID rid, List<Value> values, long lsn) throws StorageException {
        records.update(table, rid, values, lsn);
        invalidateStatistics(table);
    }

    @Override
    public void delete(String table, RID rid, long lsn) throws StorageException {
        records.delete(table, rid, lsn);
        invalidateStatistics(table);
    }

    @Override
    public void releaseReservation(InsertReservation reservation) throws StorageException {
    }

    @Override
    public void restoreRecord(String table, RID rid, RecordImage image, long lsn) throws StorageException {
        records.restoreRecord(table, rid, image, lsn);
        invalidateStatistics(table);
    }

    @Override
    public List<String> listTables() throws StorageException {
        return catalog.listTables();
    }

    @Override
    public TableSchema getSchema(String table) throws StorageException {
        return catalog.getSchema(table);
    }

    @Override
    public TableStatistics getStatistics(String table) throws StorageException {
        return statistics.getStatistics(table);
    }

    @Override
    public void createIndex(IndexMetadata definition) throws StorageException {
    }

    @Override
    public List<IndexMetadata> getIndexes(String table) throws StorageException {
        return catalog.getIndexes(table);
    }

    @Override
    public IndexStatistics getIndexStatistics(String name) throws StorageException {
        return indexes.getIndexStatistics(name);
    }

    @Override
    public List<RID> lookupIndex(String name, IndexCondition condition) throws StorageException {
        return List.of();
    }

    public void setBlockManager(BlockManager blocks) {
        this.blocks = blocks;
    }

    private void invalidateStatistics(String table) throws StorageException {
        if (statistics != null) statistics.invalidate(table);
    }

    @Override
    public synchronized void createTable(TableSchema schema) throws StorageException {
        if (schema == null) throw new StorageException(StorageErrorCode.INVALID_VALUE, "Schema must not be null");
        if (schema.tableId() == null || schema.tableId().isBlank()) {
            schema = new TableSchema(catalog.generateTableId(schema.tableName()), schema.tableName(), schema.columns(), schema.primaryKey(), schema.foreignKeys());
        }
        if (!FILE_ID.matcher(schema.tableId()).matches()) {
            throw new StorageException(StorageErrorCode.INVALID_VALUE,  "tableId must contain only letters, digits, '_' or '-': " + schema.tableId());
        }
        catalog.checkForeignKeyTargets(schema);
        Map<String, TableSchema> savedSchemas = catalog.snapshotSchemas();
        Map<String, IndexMetadata> savedIndexes = catalog.snapshotIndexes();
        try {
            catalog.registerTable(schema);
            catalog.save();
        } catch (StorageException e) {
            catalog.restore(savedSchemas, savedIndexes);
            throw e;
        }
    }

    @Override
    public synchronized void dropTable(String table, DropBehavior behavior) throws StorageException {
        if (behavior == null) throw new StorageException(StorageErrorCode.INVALID_VALUE, "DropBehavior must not be null");
        TableSchema root = catalog.getSchema(table);

        List<TableSchema> dependents = catalog.dependents(root.tableName());
        if (behavior == DropBehavior.RESTRICT && !dependents.isEmpty()) {
            throw new StorageException(StorageErrorCode.INVALID_STATE, "Cannot drop " + root.tableName()
                    + ": referenced by foreign key of " + dependents.get(0).tableName());
        }

        // Remember indexes before they disappear from the catalog (their files must go too).
        Set<String> indexFileIds = new LinkedHashSet<>();
        for (IndexMetadata index : catalog.getIndexes(root.tableName())) indexFileIds.add("idx_" + index.indexName());

        Map<String, TableSchema> savedSchemas = catalog.snapshotSchemas();
        Map<String, IndexMetadata> savedIndexes = catalog.snapshotIndexes();
        try {
            for (TableSchema child : dependents) {
                List<ForeignKeySpec> kept = child.foreignKeys().stream().filter(fk -> !fk.referencedTable().equalsIgnoreCase(root.tableName())).toList();
                catalog.replaceSchema(new TableSchema(child.tableId(), child.tableName(), child.columns(), child.primaryKey(), kept));
            }
            catalog.unregisterTable(root.tableName());
            catalog.save(); 
        } catch (StorageException e) {
            catalog.restore(savedSchemas, savedIndexes);
            throw e;
        }

        if (statistics != null) statistics.forget(root.tableName());
        BlockManager files = blocks != null ? blocks : statistics == null ? null : statistics.getBlocks();
        if (files == null) return;
        StorageException failure = null;
        List<String> ids = new ArrayList<>(indexFileIds);
        ids.add(root.tableId());
        for (String id : ids) {
            if (!FILE_ID.matcher(id).matches()) continue; // index name that never had a file
            try {
                files.deleteFile(id);
            } catch (StorageException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure; // catalog is already consistent; only orphan files remain
    }

    @Override
    public List<TableSchema> getReferencingTables(String table) throws StorageException {
        return catalog.dependents(table);
    }

    public synchronized void onSnapshotRestored() throws StorageException {
        catalog.load();
        if (statistics != null) {
            statistics.reset();
            statistics.recomputeAll();
        }
    }

    @Override
    public void flushAll() throws StorageException {
    }

    @Override
    public void close() throws StorageException {
    }
}
