package sqlike.record;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import sqlike.block.Page;
import sqlike.buffer.BufferPoolManager;
import sqlike.catalog.CatalogManager;
import sqlike.shared.InsertReservation;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import static sqlike.storage.StorageErrorCode.CORRUPTED_DATA;
import static sqlike.storage.StorageErrorCode.INVALID_RESERVATION;
import static sqlike.storage.StorageErrorCode.INVALID_STATE;
import static sqlike.storage.StorageErrorCode.INVALID_VALUE;
import static sqlike.storage.StorageErrorCode.IO_ERROR;
import static sqlike.storage.StorageErrorCode.RECORD_NOT_FOUND;
import static sqlike.storage.StorageErrorCode.TABLE_NOT_FOUND;
import sqlike.storage.StorageException;

public class RecordManager {
    private enum WriteMode { INSERT, MODIFY, RESTORE }

    private final BufferPoolManager buffer;
    private final RecordSerializer serializer;
    private final CatalogManager catalog;
    private final Map<String, InsertReservation> reservations;

    // [Helper]
    private final Map<String, InsertReservation> finishedReservations = new HashMap<>();
    // Nilai true menandai cursor yang harus gagal karena tabel berubah.
    private final Map<String, Map<RecordCursor, Boolean>> cursors = new HashMap<>();

    public RecordManager(BufferPoolManager buffer, RecordSerializer serializer, CatalogManager catalog) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.reservations = new HashMap<>();
    }

    public BufferPoolManager getBuffer() { return buffer; }
    public RecordSerializer getSerializer() { return serializer; }
    public CatalogManager getCatalog() { return catalog; }

    public synchronized Map<String, InsertReservation> getReservations() {
        return Map.copyOf(reservations);
    }

    // Public Method
    public synchronized RecordCursor scan(String table) throws StorageException {
        TableSchema schema = resolveTable(table);
        long pageCount = buffer.getPageCount(schema.tableId());

        // New RecordCursor
        RecordCursor cursor = new RecordCursor() {
            private long pageNumber;
            private int slotId;
            private SlottedPage current;
            private Record next;
            private boolean closed, exhausted;

            @Override public boolean hasNext() {
                synchronized (RecordManager.this) {
                    if (closed) throw new IllegalStateException("Cursor sudah ditutup atau gagal");
                    if (exhausted) return false;
                    if (cursors.get(schema.tableId()).get(this)) {
                        close();
                        throw new ConcurrentModificationException("Tabel berubah selama scan: " + schema.tableName());
                    }
                    if (next != null) return true;
                    try {
                        while (pageNumber < pageCount) {
                            if (current == null) {
                                PageId id = new PageId(schema.tableId(), pageNumber);
                                Page page = buffer.fetchPage(id);
                                try { current = readPage(page); }
                                finally { buffer.unpinPage(id); }
                            }
                            while (slotId < current.getSlotCount()) {
                                int slot = slotId++;
                                Optional<byte[]> bytes = current.getRecordBytes(slot);
                                if (bytes.isPresent()) {
                                    next = new Record(new RID(new PageId(schema.tableId(), pageNumber), slot),
                                            serializer.deserialize(bytes.get(), schema));
                                    return true;
                                }
                            }
                            current = null;
                            slotId = 0;
                            pageNumber++;
                        }
                        exhausted = true;
                        finish();
                        return false;
                    } catch (RuntimeException failure) {
                        closed = true;
                        finish();
                        if (failure instanceof StorageException storage && storage.getCode() != IO_ERROR
                                && storage.getCode() != CORRUPTED_DATA) throw storage;
                        if (failure instanceof StorageException || failure instanceof IllegalArgumentException
                                || failure instanceof IllegalStateException) {
                            throw new UncheckedIOException(new IOException("Gagal membaca scan tabel " + schema.tableName(), failure));
                        }
                        throw failure;
                    }
                }
            }

            @Override public Record next() {
                synchronized (RecordManager.this) {
                    if (!hasNext()) throw new NoSuchElementException("Cursor sudah habis");
                    Record record = next;
                    next = null;
                    return record;
                }
            }

            @Override public void close() {
                synchronized (RecordManager.this) {
                    closed = true;
                    finish();
                }
            }

            private void finish() {
                current = null;
                next = null;
                Map<RecordCursor, Boolean> active = cursors.get(schema.tableId());
                if (active != null) {
                    active.remove(this);
                    if (active.isEmpty()) cursors.remove(schema.tableId());
                }
            }
        };
        cursors.computeIfAbsent(schema.tableId(), ignored -> new HashMap<>()).put(cursor, false);
        return cursor;
    }

    public synchronized Optional<Record> fetch(String table, RID rid) throws StorageException {
        TableSchema schema = resolveTable(table);
        validateRid(schema, rid);
        if (rid.pageId().pageNumber() >= buffer.getPageCount(schema.tableId())) return Optional.empty();
        Page page = buffer.fetchPage(rid.pageId());
        try {
            return readPage(page).getRecordBytes(rid.slotId())
                    .map(bytes -> new Record(rid, serializer.deserialize(bytes, schema)));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new StorageException(IO_ERROR, "Page record rusak: " + rid, failure);
        } finally { buffer.unpinPage(rid.pageId()); }
    }

    public synchronized InsertReservation reserveInsert(String table, List<Value> values) throws StorageException {
        TableSchema schema = resolveTable(table);
        List<Value> snapshot = values == null ? null : new ArrayList<>(values);
        int size = serializer.serializedSize(snapshot, schema);
        if (!SlottedPage.fitsEmptyPage(buffer.getPageSize(), size)) {
            throw new StorageException(INVALID_VALUE, "Record tidak muat dalam satu page");
        }

        String tableId = schema.tableId();
        long pageCount = buffer.getPageCount(tableId);
        Optional<RID> rid = Optional.empty();
        for (long number = 0; number < pageCount && rid.isEmpty(); number++) {
            rid = reserveSlot(buffer.fetchPage(new PageId(tableId, number)), size);
        }
        RID reservedRid = rid.orElseGet(() -> reserveSlot(buffer.newPage(tableId), size).orElseThrow());
        InsertReservation reservation = new InsertReservation(UUID.randomUUID().toString(), schema.tableName(),
                reservedRid, List.copyOf(snapshot));
        reservations.put(reservation.reservationId(), reservation);
        return reservation;
    }

    private Optional<RID> reserveSlot(Page page, int size) {
        try {
            SlottedPage slotted = readPage(page);
            Optional<Integer> slot = slotted.reserveSlot(size);
            if (slot.isPresent()) savePage(page, slotted);
            return slot.map(id -> new RID(page.getPageId(), id));
        } finally { buffer.unpinPage(page.getPageId()); }
    }

    public synchronized RID insert(InsertReservation reservation, long lsn) throws StorageException {
        InsertReservation stored = requireReservation(reservation);
        if (!reservations.containsKey(stored.reservationId())) throw new StorageException(INVALID_RESERVATION, "Token tidak aktif");
        try {
            validateLsn(lsn);
            TableSchema schema = resolveTable(stored.tableName());
            validateRid(schema, stored.rid());
            byte[] bytes = serializer.serialize(stored.values(), schema);
            writeRecord(schema, stored.rid(), bytes, lsn, WriteMode.INSERT);
        } catch (RuntimeException failure) {
            try {
                releaseReservation(stored);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        finishReservation(stored);
        return stored.rid();
    }

    public synchronized void update(String table, RID rid, List<Value> values, long lsn) throws StorageException {
        validateLsn(lsn);
        TableSchema schema = resolveTable(table);
        validateRid(schema, rid);
        writeRecord(schema, rid, serializer.serialize(values, schema), lsn, WriteMode.MODIFY);
    }

    public synchronized void delete(String table, RID rid, long lsn) throws StorageException {
        validateLsn(lsn);
        TableSchema schema = resolveTable(table);
        validateRid(schema, rid);
        writeRecord(schema, rid, null, lsn, WriteMode.MODIFY);
    }

    public synchronized void releaseReservation(InsertReservation reservation) throws StorageException {
        InsertReservation stored = requireReservation(reservation);
        if (!reservations.containsKey(stored.reservationId())) return;
        RID rid = stored.rid();
        Page page = buffer.fetchPage(rid.pageId());
        try {
            SlottedPage slotted = readPage(page);
            slotted.releaseSlot(rid.slotId());
            savePage(page, slotted);
        } finally { buffer.unpinPage(rid.pageId()); }
        finishReservation(stored);
    }

    public synchronized void restoreRecord(String table, RID rid, RecordImage image, long lsn) throws StorageException {
        validateLsn(lsn);
        TableSchema schema = resolveTable(table);
        validateRid(schema, rid);
        if (image == null) throw new StorageException(INVALID_VALUE, "RecordImage tidak boleh null");
        if (image.exists() && reservations.values().stream().anyMatch(token -> token.rid().equals(rid))) {
            throw new StorageException(INVALID_STATE, "RID masih dimiliki reservasi aktif: " + rid);
        }
        writeRecord(schema, rid, image.exists() ? serializer.serialize(image.values(), schema) : null, lsn, WriteMode.RESTORE);
    }

    // Additional: clear sebuah table
    public synchronized int clearTable(String table) throws StorageException {
        TableSchema schema = resolveTable(table);
        if (cursors.containsKey(schema.tableId()) || reservations.values().stream()
                .anyMatch(token -> token.rid().pageId().tableId().equals(schema.tableId()))) {
            throw new StorageException(INVALID_STATE, "Tabel masih memiliki cursor atau reservasi aktif: " + table);
        }
        int count = 0;
        try (RecordCursor cursor = scan(schema.tableName())) {
            while (cursor.hasNext()) { cursor.next(); count = Math.incrementExact(count); }
        } catch (UncheckedIOException failure) {
            throw new StorageException(IO_ERROR, "Gagal memindai tabel sebelum clear: " + table, failure);
        } catch (ArithmeticException failure) {
            throw new StorageException(INVALID_VALUE, "Jumlah record melebihi kapasitas int", failure);
        }

        buffer.flushAll();
        buffer.truncateTable(schema.tableId());
        finishedReservations.values().removeIf(token -> token.rid().pageId().tableId().equals(schema.tableId()));
        return count;
    }

    // [Helper] Logika bersama untuk pembacaan page dan empat operasi mutasi
    private SlottedPage readPage(Page page) {
        try {
            byte[] bytes = page.getData().clone();
            boolean empty = true;
            for (byte value : bytes) {
                if (value != 0) { empty = false; break; }
            }
            SlottedPage slotted = new SlottedPage(new Page(page.getPageId(), bytes));
            if (empty) slotted.initialize();
            slotted.getSlotCount();
            return slotted;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new StorageException(IO_ERROR, "Header page record rusak: " + page.getPageId(), failure);
        }
    }

    private void writeRecord(TableSchema schema, RID rid, byte[] bytes, long lsn, WriteMode mode) {
        long count = buffer.getPageCount(schema.tableId());
        if (rid.pageId().pageNumber() >= count) {
            if (mode == WriteMode.INSERT) throw new StorageException(INVALID_RESERVATION, "RID reservasi tidak ditemukan: " + rid);
            if (mode == WriteMode.MODIFY) throw new StorageException(RECORD_NOT_FOUND, "Record tidak ditemukan: " + rid);
            if (bytes == null) return;
            while (count <= rid.pageId().pageNumber()) {
                Page allocated = buffer.newPage(schema.tableId());
                buffer.unpinPage(allocated.getPageId());
                count++;
            }
        }
        Page page = buffer.fetchPage(rid.pageId());
        try {
            SlottedPage slotted = readPage(page);
            byte[] before = slotted.getRecordBytes(rid.slotId()).orElse(null);
            if (mode == WriteMode.MODIFY && before == null) throw new StorageException(RECORD_NOT_FOUND, "Record tidak ditemukan: " + rid);
            if (before == null && bytes == null) return;
            boolean changed = !Arrays.equals(before, bytes);
            if (bytes == null) slotted.deleteSlot(rid.slotId());
            else if (mode == WriteMode.INSERT) slotted.insertAtSlot(rid.slotId(), bytes);
            else if (mode == WriteMode.MODIFY) {
                if (!slotted.updateRecordBytes(rid.slotId(), bytes)) throw new StorageException(INVALID_VALUE, "Record membesar melebihi kapasitas page: " + rid);
            } else slotted.putAtSlot(rid.slotId(), bytes);
            slotted.setPageLSN(lsn);
            savePage(page, slotted);
            if (changed) {
                Map<RecordCursor, Boolean> active = cursors.get(schema.tableId());
                if (active != null) active.replaceAll((cursor, invalid) -> true);
            }
        } catch (IllegalArgumentException failure) {
            throw new StorageException(INVALID_VALUE, "Record tidak muat pada RID " + rid, failure);
        } catch (IllegalStateException failure) {
            throw new StorageException(IO_ERROR, "Slot record rusak: " + rid, failure);
        } finally { buffer.unpinPage(rid.pageId()); }
    }

    private void savePage(Page page, SlottedPage slotted) {
        buffer.markDirty(page.getPageId(), slotted.getPageLSN());
        System.arraycopy(slotted.getPage().getData(), 0, page.getData(), 0, page.getData().length);
    }

    private void finishReservation(InsertReservation reservation) {
        reservations.remove(reservation.reservationId());
        finishedReservations.put(reservation.reservationId(), reservation);
    }

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
        InsertReservation stored = reservation == null ? null : reservations.get(reservation.reservationId());
        if (stored == null && reservation != null) stored = finishedReservations.get(reservation.reservationId());
        if (stored == null || !stored.equals(reservation)) throw new StorageException(INVALID_RESERVATION, "Token reservasi tidak dikenal atau telah diubah");
        return stored;
    }

    private void validateLsn(long lsn) throws StorageException {
        if (lsn <= 0) throw new StorageException(INVALID_VALUE, "LSN harus positif");
    }

}
