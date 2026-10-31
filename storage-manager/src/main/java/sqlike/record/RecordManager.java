package sqlike.record;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlike.buffer.BufferPoolManager;
import sqlike.catalog.CatalogManager;
import sqlike.shared.InsertReservation;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.Value;
import sqlike.storage.StorageException;

public class RecordManager {
    private BufferPoolManager buffer;
    private RecordSerializer serializer;
    private CatalogManager catalog;
    private Map<String, InsertReservation> reservations;

    public RecordManager(BufferPoolManager buffer, RecordSerializer serializer, CatalogManager catalog) {
        this.buffer = buffer;
        this.serializer = serializer;
        this.catalog = catalog;
        this.reservations = new HashMap<>();
    }

    public BufferPoolManager getBuffer() {
        return buffer;
    }

    public void setBuffer(BufferPoolManager buffer) {
        this.buffer = buffer;
    }

    public RecordSerializer getSerializer() {
        return serializer;
    }

    public void setSerializer(RecordSerializer serializer) {
        this.serializer = serializer;
    }

    public CatalogManager getCatalog() {
        return catalog;
    }

    public void setCatalog(CatalogManager catalog) {
        this.catalog = catalog;
    }

    public Map<String, InsertReservation> getReservations() {
        return reservations;
    }

    public void setReservations(Map<String, InsertReservation> reservations) {
        this.reservations = reservations;
    }

    public RecordCursor scan(String table) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public Optional<Record> fetch(String table, RID rid) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public InsertReservation reserveInsert(String table, List<Value> values) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public RID insert(InsertReservation reservation, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public void update(String table, RID rid, List<Value> values, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public void delete(String table, RID rid, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public void releaseReservation(InsertReservation reservation) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public void restoreRecord(String table, RID rid, RecordImage image, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public int clearTable(String table) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }
}
