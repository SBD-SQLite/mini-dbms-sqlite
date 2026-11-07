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

/**
 * Copy of the SM contract so the FRM adapter compiles without the SM module.
 *
 * <p>The sixteen original methods are identical to {@code storage-manager/.../IStorageManager}.
 * {@link #createSnapshot()} and {@link #restoreSnapshot(String)} are the snapshot additions fixed by
 * PRD v1.0 §3.3. The schema additions of §3.3 wait for {@code SchemaImage} in shared. Delete this
 * copy once the root {@code contracts/} source set exists.
 */
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

    /** PRD §3.3: copies the flushed database into an immutable snapshot and returns its ID. */
    String createSnapshot() throws StorageException;

    /** PRD §3.3: replaces the active data with a snapshot while offline, then reloads. */
    void restoreSnapshot(String snapshotId) throws StorageException;
}
