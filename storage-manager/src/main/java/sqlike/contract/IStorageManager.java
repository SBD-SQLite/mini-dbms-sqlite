package sqlike.contract;

import java.util.List;
import java.util.Optional;
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
import sqlike.storage.StorageException;

public interface IStorageManager {
    RecordCursor scan(String table) throws StorageException;

    Optional<Record> fetch(String table, RID rid) throws StorageException;

    InsertReservation reserveInsert(String table, List<Value> values) throws StorageException;

    RID insert(InsertReservation reservation, long lsn) throws StorageException;

    void update(String table, RID rid, List<Value> values, long lsn) throws StorageException;

    void delete(String table, RID rid, long lsn) throws StorageException;

    void releaseReservation(InsertReservation reservation) throws StorageException;

    void restoreRecord(String table, RID rid, RecordImage image, long lsn) throws StorageException;

    List<String> listTables() throws StorageException;

    TableSchema getSchema(String table) throws StorageException;

    TableStatistics getStatistics(String table) throws StorageException;

    void createIndex(IndexMetadata definition) throws StorageException;

    List<IndexMetadata> getIndexes(String table) throws StorageException;

    List<RID> lookupIndex(String name, IndexCondition condition) throws StorageException;

    void flushAll() throws StorageException;

    void close() throws StorageException;
}