package sqlike.contract;

import java.util.List;
import java.util.Optional;
import sqlike.shared.DropBehavior;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.InsertReservation;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.shared.TableStatistics;
import sqlike.shared.Value;

public interface IStorageManager {
    RecordCursor scan(String table);

    Optional<Record> fetch(String table, RID rid);

    InsertReservation reserveInsert(String table, List<Value> values);

    RID insert(InsertReservation reservation, long lsn);

    void update(String table, RID rid, List<Value> values, long lsn);

    void delete(String table, RID rid, long lsn);

    void releaseReservation(InsertReservation reservation);

    void restoreRecord(String table, RID rid, RecordImage image, long lsn);

    List<String> listTables();

    TableSchema getSchema(String table);

    TableStatistics getStatistics(String table);

    List<IndexMetadata> getIndexes(String table);

    void createIndex(IndexMetadata definition);

    List<RID> lookupIndex(String name, IndexCondition condition);

    void flushAll();

    default void close() {
    }

    // Class diagram Tab 6 signatures
    default void createTable(TableSchema schema) {
        throw new UnsupportedOperationException("CREATE TABLE requires a WAL LSN");
    }

    default void dropTable(String table, DropBehavior behavior) {
        throw new UnsupportedOperationException("DROP TABLE requires a WAL LSN");
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
