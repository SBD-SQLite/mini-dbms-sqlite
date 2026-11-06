package sqlike.contract;

import java.util.List;
import java.util.Optional;
import sqlike.shared.*;
import sqlike.shared.Record;


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
}
