package sqlike.contract;

import java.util.List;
import java.util.Optional;
import sqlike.shared.DropBehavior;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexStatistics;
import sqlike.shared.InsertReservation;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.shared.TableStatistics;
import sqlike.shared.Value;
import sqlike.storage.StorageErrorCode;
import sqlike.storage.StorageException;

/**
 * Storage Manager contract. Union of every method that SM, QP, QO and FRM declared in their former
 * local copies. Implemented by the SM group.
 */
public interface IStorageManager {
    RecordCursor scan(String table) throws StorageException;

    Optional<Record> fetch(String table, RID rid) throws StorageException;

    InsertReservation reserveInsert(String table, List<Value> values) throws StorageException;

    RID insert(InsertReservation reservation, long lsn) throws StorageException;

    void update(String table, RID rid, List<Value> values, long lsn) throws StorageException;

    void delete(String table, RID rid, long lsn) throws StorageException;

    default int clearTable(String table) throws StorageException {
        throw new UnsupportedOperationException("clearTable is not implemented");
    }

    void releaseReservation(InsertReservation reservation) throws StorageException;

    void restoreRecord(String table, RID rid, RecordImage image, long lsn) throws StorageException;

    List<String> listTables() throws StorageException;

    TableSchema getSchema(String table) throws StorageException;

    TableStatistics getStatistics(String table) throws StorageException;

    void createIndex(IndexMetadata definition) throws StorageException;

    List<IndexMetadata> getIndexes(String table) throws StorageException;

    IndexStatistics getIndexStatistics(String name) throws StorageException;

    List<RID> lookupIndex(String name, IndexCondition condition) throws StorageException;

    default void createTable(TableSchema schema) throws StorageException {
        throw new StorageException(StorageErrorCode.UNSUPPORTED_OPERATION, "createTable is not supported");
    }

    default void dropTable(String table, DropBehavior behavior) throws StorageException {
        throw new StorageException(StorageErrorCode.UNSUPPORTED_OPERATION, "dropTable is not supported");
    }

    default List<TableSchema> getReferencingTables(String table) throws StorageException {
        throw new StorageException(StorageErrorCode.UNSUPPORTED_OPERATION, "getReferencingTables is not supported");
    }

    void flushAll() throws StorageException;

    default void close() throws StorageException {
    }

    // PRD §3.3 LSN-bearing schema & snapshot signatures
    default void createTable(TableSchema schema, long lsn) {
        throw new UnsupportedOperationException("createTable is not implemented");
    }

    default void dropTable(String table, DropBehavior behavior, long lsn) {
        throw new UnsupportedOperationException("dropTable is not implemented");
    }

    default void restoreSchema(SchemaImage image, long lsn) {
        throw new UnsupportedOperationException("restoreSchema is not implemented");
    }

    default void createIndex(IndexMetadata definition, long lsn) {
        throw new UnsupportedOperationException("LSN-bearing createIndex is not implemented");
    }

    default String createSnapshot() {
        throw new UnsupportedOperationException("Snapshots are not implemented");
    }

    default void restoreSnapshot(String snapshotId) {
        throw new UnsupportedOperationException("Snapshot restore is not implemented");
    }
}
