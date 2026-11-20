package sqlike;

import static org.junit.jupiter.api.Assertions.*;
import static sqlike.Fixtures.*;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sqlike.shared.InsertReservation;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.storage.StorageErrorCode;

/** Record CRUD, reservations, cursors, restoreRecord (SM-10 .. SM-12). */
class RecordManagerTest {
    @TempDir Path dir;
    Db db;

    @BeforeEach void setUp() {
        db = new Db(dir, 512, 16);
        db.sm.createTable(items("t_items", "items"));
    }

    @AfterEach void tearDown() {
        try { db.sm.close(); } catch (RuntimeException ignored) { }
    }

    @Test void reserveInsertIsInvisibleUntilInserted() {
        InsertReservation r = db.sm.reserveInsert("items", item(1, "a", 1, "A"));
        assertTrue(db.sm.fetch("items", r.rid()).isEmpty());
        assertTrue(scan(db.sm, "items").isEmpty());
        RID rid = db.sm.insert(r, db.next());
        assertEquals(r.rid(), rid);
        assertEquals(item(1, "a", 1, "A"), db.sm.fetch("items", rid).orElseThrow().values());
    }

    @Test void activeReservationsGetDistinctSlots() {
        InsertReservation a = db.sm.reserveInsert("items", item(1, "a", 1, "A"));
        InsertReservation b = db.sm.reserveInsert("items", item(2, "b", 1, "B"));
        assertNotEquals(a.rid(), b.rid());
        assertNotEquals(a.reservationId(), b.reservationId());
    }

    @Test void releasedReservationCannotBeInserted() {
        InsertReservation r = db.sm.reserveInsert("items", item(1, "a", 1, "A"));
        db.sm.releaseReservation(r);
        assertEquals(StorageErrorCode.INVALID_RESERVATION, codeOf(() -> db.sm.insert(r, db.next())));
        assertDoesNotThrow(() -> db.sm.releaseReservation(r), "release twice is a no-op");
        assertTrue(scan(db.sm, "items").isEmpty());
    }

    @Test void forgedOrTamperedReservationIsRejected() {
        InsertReservation r = db.sm.reserveInsert("items", item(1, "a", 1, "A"));
        InsertReservation tampered = new InsertReservation(r.reservationId(), r.tableName(), r.rid(), item(9, "z", 9, "Z"));
        assertEquals(StorageErrorCode.INVALID_RESERVATION, codeOf(() -> db.sm.insert(tampered, db.next())));
        InsertReservation forged = new InsertReservation("nope", "items", r.rid(), r.values());
        assertEquals(StorageErrorCode.INVALID_RESERVATION, codeOf(() -> db.sm.insert(forged, db.next())));
        db.sm.releaseReservation(r);
    }

    @Test void insertTwiceWithSameReservationFails() {
        InsertReservation r = db.sm.reserveInsert("items", item(1, "a", 1, "A"));
        db.sm.insert(r, db.next());
        assertEquals(StorageErrorCode.INVALID_RESERVATION, codeOf(() -> db.sm.insert(r, db.next())));
        assertEquals(1, scan(db.sm, "items").size());
    }

    @Test void reserveRejectsInvalidValuesAndOversizeRecord() {
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.reserveInsert("items", List.of(i(1)))));
        var big = new sqlike.shared.TableSchema("t_big", "big", List.of(col("x", sqlike.shared.DataType.VARCHAR, 5000)));
        db.sm.createTable(big);
        assertEquals(StorageErrorCode.INVALID_VALUE,
                codeOf(() -> db.sm.reserveInsert("big", List.of(vc("x".repeat(4000))))));
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> db.sm.reserveInsert("ghost", item(1, "a", 1, "A"))));
    }

    @Test void insertRejectsNonPositiveLsn() {
        InsertReservation r = db.sm.reserveInsert("items", item(1, "a", 1, "A"));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.insert(r, 0)));
        InsertReservation r2 = db.sm.reserveInsert("items", item(2, "b", 1, "B"));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.insert(r2, -3)));
        assertTrue(scan(db.sm, "items").isEmpty());
    }

    @Test void updateReplacesValuesAndKeepsRid() {
        RID rid = db.insert("items", item(1, "a", 1, "A"));
        db.sm.update("items", rid, item(1, "bb", 2, "B"), db.next());
        assertEquals(item(1, "bb", 2, "B"), db.sm.fetch("items", rid).orElseThrow().values());
    }

    @Test void deleteRemovesRecordAndSecondDeleteReportsNotFound() {
        RID rid = db.insert("items", item(1, "a", 1, "A"));
        db.sm.delete("items", rid, db.next());
        assertTrue(db.sm.fetch("items", rid).isEmpty());
        assertEquals(StorageErrorCode.RECORD_NOT_FOUND, codeOf(() -> db.sm.delete("items", rid, db.next())));
        assertEquals(StorageErrorCode.RECORD_NOT_FOUND,
                codeOf(() -> db.sm.update("items", rid, item(1, "a", 1, "A"), db.next())));
    }

    @Test void ridValidation() {
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.fetch("items", null)));
        assertEquals(StorageErrorCode.INVALID_VALUE,
                codeOf(() -> db.sm.fetch("items", new RID(new PageId("other_table", 0), 0))));
        assertEquals(StorageErrorCode.INVALID_VALUE,
                codeOf(() -> db.sm.fetch("items", new RID(new PageId("t_items", 0), -1))));
        assertTrue(db.sm.fetch("items", new RID(new PageId("t_items", 99), 0)).isEmpty());
    }

    @Test void scanReturnsAllLiveRecordsAcrossPagesWithUniqueRids() {
        Set<List<?>> expected = new HashSet<>();
        for (int k = 0; k < 60; k++) {
            db.insert("items", item(k, "n" + k, k, "C"));
            expected.add(item(k, "n" + k, k, "C"));
        }
        assertTrue(db.blocks.getPageCount("t_items") > 1);
        List<Record> all = scan(db.sm, "items");
        assertEquals(60, all.size());
        assertEquals(60, all.stream().map(Record::rid).distinct().count());
        assertEquals(expected, new HashSet<>(all.stream().map(Record::values).toList()));
    }

    @Test void scanSkipsDeletedAndNeverShowsReservations() {
        RID a = db.insert("items", item(1, "a", 1, "A"));
        db.insert("items", item(2, "b", 1, "B"));
        db.sm.reserveInsert("items", item(3, "c", 1, "C"));
        db.sm.delete("items", a, db.next());
        assertEquals(List.of(item(2, "b", 1, "B")), scan(db.sm, "items").stream().map(Record::values).toList());
    }

    @Test void closedCursorRefusesUseAndExhaustedCursorReturnsFalse() {
        db.insert("items", item(1, "a", 1, "A"));
        RecordCursor c = db.sm.scan("items");
        assertTrue(c.hasNext());
        c.next();
        assertFalse(c.hasNext());
        assertThrows(java.util.NoSuchElementException.class, c::next);
        RecordCursor d = db.sm.scan("items");
        d.close();
        assertThrows(IllegalStateException.class, d::hasNext);
    }

    @Test void restoreRecordUndoesInsertUpdateAndDelete() {
        RID rid = db.insert("items", item(1, "old", 1, "A"));
        db.sm.update("items", rid, item(1, "new", 2, "B"), db.next());
        db.sm.restoreRecord("items", rid, new RecordImage(true, item(1, "old", 1, "A")), db.next());
        assertEquals(item(1, "old", 1, "A"), db.sm.fetch("items", rid).orElseThrow().values());
        db.sm.restoreRecord("items", rid, new RecordImage(false, List.of()), db.next());
        assertTrue(db.sm.fetch("items", rid).isEmpty());
        db.sm.restoreRecord("items", rid, new RecordImage(true, item(1, "back", 1, "A")), db.next());
        assertEquals(item(1, "back", 1, "A"), db.sm.fetch("items", rid).orElseThrow().values());
    }

    @Test void restoreRecordIsIdempotentAndCanRecreateAtFarRid() {
        RID far = new RID(new PageId("t_items", 3), 2);
        RecordImage image = new RecordImage(true, item(7, "far", 1, "F"));
        db.sm.restoreRecord("items", far, image, db.next());
        db.sm.restoreRecord("items", far, image, db.next());
        assertEquals(item(7, "far", 1, "F"), db.sm.fetch("items", far).orElseThrow().values());
        assertTrue(db.blocks.getPageCount("t_items") >= 4);
        db.sm.restoreRecord("items", new RID(new PageId("t_items", 50), 0), new RecordImage(false, List.of()), db.next());
        assertTrue(db.blocks.getPageCount("t_items") < 50, "restoring 'absent' must not allocate pages");
    }

    @Test void restoreRecordRefusesRidHeldByActiveReservationAndNullImage() {
        InsertReservation r = db.sm.reserveInsert("items", item(1, "a", 1, "A"));
        assertEquals(StorageErrorCode.INVALID_STATE,
                codeOf(() -> db.sm.restoreRecord("items", r.rid(), new RecordImage(true, item(1, "a", 1, "A")), db.next())));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.restoreRecord("items", r.rid(), null, db.next())));
        db.sm.releaseReservation(r);
    }

    @Test void pageLsnIsRecordedAndWalIsForcedBeforeFlush() {
        long lsn = 5000;
        InsertReservation r = db.sm.reserveInsert("items", item(1, "a", 1, "A"));
        db.sm.insert(r, lsn);
        db.sm.flushAll();
        assertTrue(db.wal.max() >= lsn, "WAL must be made durable up to the page LSN before page write");
        var page = db.buffer.fetchPage(r.rid().pageId());
        assertEquals(lsn, new sqlike.record.SlottedPage(page).getPageLSN());
        db.buffer.unpinPage(page.getPageId());
    }

    @Test void walFailureAbortsFlushButDataStaysInMemory() {
        RID rid = db.insert("items", item(1, "a", 1, "A"));
        db.wal.reject = true;
        assertEquals(StorageErrorCode.WAL_FAILURE, codeOf(() -> db.sm.flushAll()));
        assertEquals(item(1, "a", 1, "A"), db.sm.fetch("items", rid).orElseThrow().values());
        db.wal.reject = false;
        assertDoesNotThrow(() -> db.sm.flushAll());
    }

    @Test void dataSurvivesRestart() {
        RID rid = db.insert("items", item(1, "persist", 1, "A"));
        db.sm.close();
        try (Db again = new Db(dir, 512, 16)) {
            assertEquals(item(1, "persist", 1, "A"), again.sm.fetch("items", rid).orElseThrow().values());
            assertEquals(1, scan(again.sm, "items").size());
        }
    }

    @Test void tableIsolation() {
        db.sm.createTable(items("t_other", "other"));
        db.insert("items", item(1, "a", 1, "A"));
        assertTrue(scan(db.sm, "other").isEmpty());
        RID foreign = db.insert("items", item(2, "b", 1, "B"));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.fetch("other", foreign)));
    }
}
