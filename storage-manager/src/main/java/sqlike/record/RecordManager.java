package sqlike.record;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import sqlike.buffer.BufferPoolManager;
import sqlike.catalog.CatalogManager;
import sqlike.shared.InsertReservation;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import sqlike.storage.StorageException;

import static sqlike.storage.StorageErrorCode.*;

// [Masih berupa validation]
public class RecordManager {
    private final BufferPoolManager buffer;
    private final RecordSerializer serializer;
    private final CatalogManager catalog;
    private final Map<String, InsertReservation> reservations;

    public RecordManager(BufferPoolManager buffer, RecordSerializer serializer, CatalogManager catalog) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.reservations = new HashMap<>();
    }

    public BufferPoolManager getBuffer() {
        return buffer;
    }

    public RecordSerializer getSerializer() {
        return serializer;
    }

    public CatalogManager getCatalog() {
        return catalog;
    }

    public Map<String, InsertReservation> getReservations() {
        return Map.copyOf(reservations);
    }

    public RecordCursor scan(String table) throws StorageException {
        resolveTable(table);
        throw storagePending("scan");
    }

    public Optional<Record> fetch(String table, RID rid) throws StorageException {
        validateRid(resolveTable(table), rid);
        throw storagePending("fetch");
    }

    public InsertReservation reserveInsert(String table, List<Value> values) throws StorageException {
        serializer.validate(values, resolveTable(table));
        throw storagePending("reserveInsert");
    }

    public RID insert(InsertReservation reservation, long lsn) throws StorageException {
        validateLsn(lsn);

        InsertReservation stored = requireReservation(reservation);
        TableSchema schema = resolveTable(stored.tableName());
        validateRid(schema, stored.rid());
        serializer.validate(stored.values(), schema);

        throw storagePending("insert");
    }

    public void update(String table, RID rid, List<Value> values, long lsn) throws StorageException {
        validateLsn(lsn);

        TableSchema schema = resolveTable(table);
        validateRid(schema, rid);
        serializer.validate(values, schema);
        
        throw storagePending("update");
    }

    public void delete(String table, RID rid, long lsn) throws StorageException {
        validateLsn(lsn);

        validateRid(resolveTable(table), rid);

        throw storagePending("delete");
    }

    public void releaseReservation(InsertReservation reservation) throws StorageException {
        requireReservation(reservation);

        throw storagePending("releaseReservation");
    }

    public void restoreRecord(String table, RID rid, RecordImage image, long lsn) throws StorageException {
        validateLsn(lsn);

        TableSchema schema = resolveTable(table);
        validateRid(schema, rid);
        if (image == null) throw new StorageException(INVALID_VALUE, "RecordImage tidak boleh null");
        if (image.exists()) serializer.validate(image.values(), schema);

        throw storagePending("restoreRecord");
    }

    public int clearTable(String table) throws StorageException {
        resolveTable(table);
        
        throw storagePending("clearTable offline");
    }

    // Helper Functions
    private TableSchema resolveTable(String table) throws StorageException {
        if (table == null || table.isBlank()) throw new StorageException(INVALID_VALUE, "Nama tabel tidak valid");
        if (!catalog.tableExists(table)) throw new StorageException(TABLE_NOT_FOUND, "Tabel tidak ditemukan: " + table);
        return catalog.getSchema(table);
    }

    private void validateRid(TableSchema schema, RID rid) throws StorageException {
        if (rid == null || rid.pageId() == null || rid.pageId().pageNumber() < 0 || rid.slotId() < 0 || !schema.tableId().equals(rid.pageId().tableId())) {
            throw new StorageException(INVALID_VALUE, "RID tidak valid untuk tabel " + schema.tableName());
        }
    }

    private InsertReservation requireReservation(InsertReservation reservation) throws StorageException {
        if (reservation == null || reservation.reservationId() == null || !reservation.equals(reservations.get(reservation.reservationId()))) {
            throw new StorageException(INVALID_RESERVATION, "Token reservasi tidak dikenal atau telah diubah");
        }
        return reservations.get(reservation.reservationId());
    }

    private void validateLsn(long lsn) throws StorageException {
        if (lsn <= 0) throw new StorageException(INVALID_VALUE, "LSN harus positif");
    }

    private StorageException storagePending(String operation) {
        return new StorageException(INVALID_STATE, operation + " menunggu API page, slot, dan WAL dari SM2/SM3");
    }
}
