package sqlike.storage;

import java.util.List;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
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
        return null;
    }

    @Override
    public void update(String table, RID rid, List<Value> values, long lsn) throws StorageException {
    }

    @Override
    public void delete(String table, RID rid, long lsn) throws StorageException {
    }

    @Override
    public void releaseReservation(InsertReservation reservation) throws StorageException {
    }

    @Override
    public void restoreRecord(String table, RID rid, RecordImage image, long lsn) throws StorageException {
    }

    @Override
    public List<String> listTables() throws StorageException {
        return List.of();
    }

    @Override
    public TableSchema getSchema(String table) throws StorageException {
        return null;
    }

    @Override
    public TableStatistics getStatistics(String table) throws StorageException {
        return null;
    }

    @Override
    public void createIndex(IndexMetadata definition) throws StorageException {
    }

    @Override
    public List<IndexMetadata> getIndexes(String table) throws StorageException {
        return List.of();
    }

    @Override
    public List<RID> lookupIndex(String name, IndexCondition condition) throws StorageException {
        return List.of();
    }

    @Override
    public void flushAll() throws StorageException {
    }

    @Override
    public void close() throws StorageException {
    }
}