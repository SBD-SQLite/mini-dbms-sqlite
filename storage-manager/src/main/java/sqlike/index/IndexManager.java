package sqlike.index;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import sqlike.buffer.BufferPoolManager;
import sqlike.catalog.CatalogManager;
import sqlike.record.RecordManager;
import sqlike.record.RecordSerializer;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexOperator;
import sqlike.shared.IndexStatistics;
import sqlike.shared.IndexType;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import static sqlike.storage.StorageErrorCode.INDEX_NOT_FOUND;
import static sqlike.storage.StorageErrorCode.INVALID_STATE;
import static sqlike.storage.StorageErrorCode.INVALID_VALUE;
import static sqlike.storage.StorageErrorCode.IO_ERROR;
import static sqlike.storage.StorageErrorCode.TABLE_NOT_FOUND;
import static sqlike.storage.StorageErrorCode.UNSUPPORTED_OPERATION;
import sqlike.storage.StorageException;

public class IndexManager {
    private final CatalogManager catalog;
    private final RecordManager records;
    private final Map<String, Index> indexes = new HashMap<>();

    private final BufferPoolManager buffer;
    private final RecordSerializer serializer;

    private final LongSupplier buildLsn;

    public IndexManager(CatalogManager catalog, RecordManager records, BufferPoolManager buffer, RecordSerializer serializer, LongSupplier buildLsn) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.records = Objects.requireNonNull(records, "records");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.buildLsn = Objects.requireNonNull(buildLsn, "buildLsn");
    }

    // [Public Method] - lihat diagram untuk list-list
    // 1. Create an index
    public void createIndex(IndexMetadata definition) throws StorageException {
        synchronized (records) {
            // Checking
            if (definition == null || definition.indexName() == null || definition.indexName().isBlank() || !definition.indexName().equals(definition.indexName().strip()) || definition.tableName() == null || definition.tableName().isBlank()
                || definition.columnName() == null || definition.columnName().isBlank() || definition.indexType() == null) {
                throw new StorageException(INVALID_VALUE, "Invalid index definition");
            }

            String requestedName = definition.indexName();
            if (catalog.getIndexes().values().stream().anyMatch(existing -> existing.indexName().equalsIgnoreCase(requestedName)) || indexes.containsKey(requestedName)) {
                throw new StorageException(INVALID_VALUE, "Index exists: " + definition.indexName());
            }

            if (!catalog.tableExists(definition.tableName())) {
                throw new StorageException(TABLE_NOT_FOUND, "Table not found: " + definition.tableName());
            }

            TableSchema schema = catalog.getSchema(definition.tableName());
            int column = resolveColumn(schema, definition);
            if (column < 0) throw new StorageException(INVALID_VALUE, "Index column not found: " + definition.columnName());

            definition = new IndexMetadata(definition.indexName(), schema.tableName(), schema.columns().get(column).name(), definition.indexType());
            Index index = buildIndex(definition, schema, column, false);
            try {
                catalog.registerIndex(definition);
                catalog.save();
                indexes.put(definition.indexName(), index);
            } catch (RuntimeException failure) {
                try {
                    catalog.getIndexes().remove(definition.indexName(), definition);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                discard(index, failure);
                if (failure instanceof StorageException storage) throw storage;
                throw new StorageException(failure instanceof UncheckedIOException ? IO_ERROR : INVALID_STATE, "Index build failed: " + definition.indexName(), failure);
            }
        }
    }

    // 2. Lookup index
    public List<RID> lookup(String name, IndexCondition condition) throws StorageException {
        synchronized (records) {
            // Checking
            if (name == null || name.isBlank()) throw new StorageException(INVALID_VALUE, "Invalid index name");

            IndexMetadata definition = catalog.getIndexes().values().stream()
                    .filter(candidate -> candidate.indexName().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (definition == null) throw new StorageException(INDEX_NOT_FOUND, "Index not found: " + name);

            Index index = indexes.get(definition.indexName());
            if (index == null) throw new StorageException(INVALID_STATE, "Index is not loaded: " + name);

            if (condition == null || condition.operator() == null || condition.upperBound() == null) {
                throw new StorageException(INVALID_VALUE, "Invalid index condition");
            }

            TableSchema schema = catalog.getSchema(definition.tableName());
            int column = resolveColumn(schema, definition);
            if (column < 0) throw new StorageException(INVALID_STATE, "Index column missing: " + name);

            // Lookup Process
            IndexHelper keys = new IndexHelper(schema.columns().get(column), serializer);
            keys.validateLookupKey(condition.key());

            if (definition.indexType() == IndexType.HASH && condition.operator() != IndexOperator.EQ) {
                throw new StorageException(UNSUPPORTED_OPERATION, "HashIndex only supports EQ");
            }
            if (condition.operator() == IndexOperator.BETWEEN) {
                if (condition.upperBound().isEmpty()) throw new StorageException(INVALID_VALUE, "BETWEEN needs an upper bound");

                int comparison = keys.compare(condition.key(), condition.upperBound().get());
                if (comparison > 0) throw new StorageException(INVALID_VALUE, "Lower bound exceeds upper bound");
                if (comparison == 0 && (!condition.lowerInclusive() || !condition.upperInclusive())) return List.of();
            }

            if (condition.operator() == IndexOperator.EQ) return List.copyOf(index.search(condition.key()));

            if (!(index instanceof BPlusTree tree)) throw new StorageException(INVALID_STATE, "Invalid B+Tree instance: " + name);

            return List.copyOf(tree.searchRange(condition));
        }
    }

    // 3-5. Insert, Delete, and Update
    public void onInsert(String table, Record record, long lsn) throws StorageException {
        onUpdate(table, null, record, lsn);
    }

    public void onDelete(String table, Record record, long lsn) throws StorageException {
        onUpdate(table, record, null, lsn);
    }

    public void onUpdate(String table, Record before, Record after, long lsn) throws StorageException {
        synchronized (records) {
            // Checking
            if (table == null || table.isBlank() || lsn <= 0 || (before == null && after == null)) {
                throw new StorageException(INVALID_VALUE, "Invalid index mutation arguments");
            }

            if (!catalog.tableExists(table)) throw new StorageException(TABLE_NOT_FOUND, "Table not found: " + table);

            TableSchema schema = catalog.getSchema(table);
            RID rid = after == null ? before.rid() : after.rid();
            if (rid == null || rid.pageId() == null || !schema.tableId().equals(rid.pageId().tableId()) || rid.pageId().pageNumber() < 0
                || rid.slotId() < 0 || (before != null && !rid.equals(before.rid()))) {
                throw new StorageException(INVALID_VALUE, "Index mutation needs the same valid table RID");
            }

            if (before != null) serializer.validate(before.values(), schema);
            if (after != null) serializer.validate(after.values(), schema);
            List<Change> changes = new ArrayList<>();

            for (IndexMetadata definition : catalog.getIndexes(table)) {
                Index index = indexes.get(definition.indexName());
                if (index == null) throw new StorageException(INVALID_STATE, "Index is not loaded: " + definition.indexName());

                int column = resolveColumn(schema, definition);
                if (column < 0) throw new StorageException(INVALID_STATE, "Index column missing: " + definition.indexName());

                Value oldKey = before == null ? null : before.values().get(column);
                Value newKey = after == null ? null : after.values().get(column);

                IndexHelper keys = new IndexHelper(schema.columns().get(column), serializer);

                if (oldKey == null || newKey == null || keys.compare(oldKey, newKey) != 0) {
                    changes.add(new Change(index, oldKey, newKey));
                }
            }

            List<Change> applied = new ArrayList<>();
            try {
                for (Change change : changes) {
                    if (change.before() != null) {
                        change.index().remove(change.before(), rid, lsn);
                        applied.add(new Change(change.index(), change.before(), null));
                    }
                    if (change.after() != null) {
                        change.index().insert(change.after(), rid, lsn);
                        applied.add(new Change(change.index(), null, change.after()));
                    }
                }
            } catch (RuntimeException failure) {
                for (int i = applied.size() - 1; i >= 0; i--) {
                    Change change = applied.get(i);
                    try {
                        if (change.after() != null) change.index().remove(change.after(), rid, lsn);
                        else change.index().insert(change.before(), rid, lsn);
                    } catch (RuntimeException rollback) {
                        failure.addSuppressed(rollback);
                        indexes.values().removeIf(index -> index == change.index());
                    }
                }
                if (failure instanceof StorageException storage) throw storage;
                throw new StorageException(INVALID_STATE, "Index mutation failed", failure);
            }
        }
    }

    // 6. Load Index
    public void loadIndexes() throws StorageException {
        synchronized (records) {
            // Checking
            if (!indexes.isEmpty()) throw new StorageException(INVALID_STATE, "Indexes are already loaded");

            Map<String, Index> loaded = new HashMap<>();

            try {
                for (IndexMetadata definition : List.copyOf(catalog.getIndexes().values())) {
                    if (definition.indexName() == null || definition.indexName().isBlank() || definition.tableName() == null || definition.tableName().isBlank()
                        || definition.columnName() == null || definition.columnName().isBlank() || definition.indexType() == null || loaded.containsKey(definition.indexName())) {
                        throw new StorageException(INVALID_STATE, "Invalid catalog index definition");
                    }

                    if (!catalog.tableExists(definition.tableName())) {
                        throw new StorageException(TABLE_NOT_FOUND, "Index table missing: " + definition.tableName());
                    }

                    TableSchema schema = catalog.getSchema(definition.tableName());
                    int column = resolveColumn(schema, definition);

                    if (column < 0) {
                        throw new StorageException(INVALID_STATE, "Invalid catalog index: " + definition.indexName());
                    }

                    loaded.put(definition.indexName(), buildIndex(definition, schema, column, true));
                }
                indexes.putAll(loaded);
            } catch (RuntimeException failure) {
                for (Index index : loaded.values()) discard(index, failure);

                if (failure instanceof StorageException storage) throw storage;
                throw new StorageException(failure instanceof UncheckedIOException ? IO_ERROR : INVALID_STATE, "Index rebuild failed ", failure);
            }
        }
    }

    // [Additional] 7. Flush
    public void flushAll() throws StorageException {
        synchronized (records) {
            for (Index index : indexes.values()) index.flush();
        }
    }

    // 8. Get Index Statistic
    public IndexStatistics getIndexStatistics(String name) throws StorageException {
        synchronized (records) {
            if (name == null || name.isBlank()) throw new StorageException(INVALID_VALUE, "Invalid index name");
            IndexMetadata definition = catalog.getIndexes().values().stream()
                    .filter(candidate -> candidate.indexName().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (definition == null) throw new StorageException(INDEX_NOT_FOUND, "Index not found: " + name);
            Index index = indexes.get(definition.indexName());
            if (index == null) throw new StorageException(INVALID_STATE, "Index is not loaded: " + name);
            return index.getStatistics();
        }
    }

    // Helper Functions
    private int resolveColumn(TableSchema schema, IndexMetadata definition) {
        for (int i = 0; i < schema.columns().size(); i++) {
            if (schema.columns().get(i).name().equalsIgnoreCase(definition.columnName())) return i;
        }
        return -1;
    }

    private Index buildIndex(IndexMetadata definition, TableSchema schema, int column, boolean rebuild) throws StorageException {
        IndexHelper keys = new IndexHelper(schema.columns().get(column), serializer);
        long lsn = buildLsn.getAsLong();
        if (lsn <= 0) throw new StorageException(INVALID_STATE, "Index build needs a valid coordinator WAL boundary");
        String storageId = IndexHelper.storageId(definition);
        if (catalog.getSchemas().values().stream().anyMatch(table -> table.tableId().equals(storageId))) {
            throw new StorageException(INVALID_VALUE, "Index namespace collides with a table: " + storageId);
        }
        Index index = definition.indexType() == IndexType.HASH
                ? new HashIndex(buffer, definition, keys, schema.tableId()) : new BPlusTree(buffer, definition, keys, schema.tableId());
        try {
            if (rebuild) buffer.truncateTable(storageId);
            if (index instanceof HashIndex hash) hash.initialize(lsn);
            else ((BPlusTree) index).initialize(lsn);
            try (RecordCursor cursor = records.scan(definition.tableName())) {
                while (cursor.hasNext()) {
                    Record record = cursor.next();
                    serializer.validate(record.values(), schema);
                    index.insert(record.values().get(column), record.rid(), lsn);
                }
            }
            return index;
        } catch (RuntimeException failure) {
            discard(index, failure);
            if (failure instanceof StorageException storage) throw storage;
            throw new StorageException(failure instanceof UncheckedIOException ? IO_ERROR : INVALID_STATE,
                    "Index build failed: " + definition.indexName(), failure);
        }
    }

    private void discard(Index index, Throwable failure) {
        try {
            if (index instanceof HashIndex hash) hash.discard();
            else if (index instanceof BPlusTree tree) tree.discard();
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private record Change(Index index, Value before, Value after) { }

}
