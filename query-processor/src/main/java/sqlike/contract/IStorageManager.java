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

/**
 * Storage Manager contract interface from SQLike supergroup specification.
 */
public interface IStorageManager {
    RecordCursor scan(String table) throws Exception;

    Optional<Record> fetch(String table, RID rid) throws Exception;

    InsertReservation reserveInsert(String table, List<Value> values) throws Exception;

    RID insert(InsertReservation reservation, long lsn) throws Exception;

    void update(String table, RID rid, List<Value> values, long lsn) throws Exception;

    void delete(String table, RID rid, long lsn) throws Exception;

    void releaseReservation(InsertReservation reservation) throws Exception;

    void restoreRecord(String table, RID rid, RecordImage image, long lsn) throws Exception;

    List<String> listTables() throws Exception;

    TableSchema getSchema(String table) throws Exception;

    TableStatistics getStatistics(String table) throws Exception;

    void createIndex(IndexMetadata definition) throws Exception;

    List<IndexMetadata> getIndexes(String table) throws Exception;

    List<RID> lookupIndex(String name, IndexCondition condition) throws Exception;

    void flushAll() throws Exception;
}
