package sqlike;

import static org.junit.jupiter.api.Assertions.*;
import static sqlike.Fixtures.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sqlike.contract.IStorageManager;
import sqlike.shared.DataType;
import sqlike.shared.DropBehavior;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexOperator;
import sqlike.shared.IndexType;
import sqlike.shared.RID;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.storage.StorageManager;

/**
 * What QP/QO/FRM (including the QP-15/16 and QO-17/18 bonus features) need from the SM.
 * "Gaps" tests state the required behaviour; they fail until the SM implements it.
 */
class BonusAndContractTest {
    @TempDir Path dir;
    Db db;

    @BeforeEach void setUp() {
        db = new Db(dir, 512, 32);
        db.sm.createTable(items("t_items", "items"));
    }

    @AfterEach void tearDown() {
        try { db.sm.close(); } catch (RuntimeException ignored) { }
    }

    @Test void storageManagerImplementsTheSharedContract() {
        assertTrue(IStorageManager.class.isAssignableFrom(StorageManager.class));
        assertInstanceOf(IStorageManager.class, db.sm);
    }

    @Test void optimizerInputsAreAvailable_statisticsAndIndexMetadata() {
        for (int k = 0; k < 20; k++) db.insert("items", item(k, "n" + (k % 4), k, "C"));
        db.sm.createIndex(new IndexMetadata("ix", "items", "id", IndexType.BPLUS_TREE));
        var stats = db.sm.getStatistics("items");
        assertEquals(20, stats.recordCount());
        assertEquals(4L, stats.distinctCounts().get("name"));
        assertTrue(stats.blockingFactor() > 0 && stats.blockCount() > 0);
        assertEquals(List.of("ix"), db.sm.getIndexes("items").stream().map(IndexMetadata::indexName).toList());
        assertTrue(db.sm.getIndexStatistics("ix").height().isPresent());
    }

    @Test void indexScanBonus_rangePredicateReturnsOnlyMatchingRids() {
        for (int k = 0; k < 50; k++) db.insert("items", item(k, "n", k, "C"));
        db.sm.createIndex(new IndexMetadata("ix", "items", "id", IndexType.BPLUS_TREE));
        List<RID> hits = db.sm.lookupIndex("ix",
                new IndexCondition(IndexOperator.BETWEEN, i(10), Optional.of(i(14)), true, true));
        assertEquals(5, hits.size());
        assertTrue(hits.stream().allMatch(r -> db.sm.fetch("items", r).isPresent()));
    }

    @Test void executorPatternCollectRidsThenMutateWorks() {
        for (int k = 0; k < 30; k++) db.insert("items", item(k, "n", k, "C"));
        List<RID> targets;
        try (RecordCursor c = db.sm.scan("items")) {
            targets = new java.util.ArrayList<>();
            while (c.hasNext()) targets.add(c.next().rid());
        }
        for (RID rid : targets) db.sm.delete("items", rid, db.next());
        assertTrue(scan(db.sm, "items").isEmpty());
    }

    @Test void cursorIsInvalidatedWhenTheTableChangesDuringScan() {
        db.insert("items", item(1, "a", 1, "C"));
        db.insert("items", item(2, "b", 1, "C"));
        RecordCursor c = db.sm.scan("items");
        c.next();
        db.insert("items", item(3, "c", 1, "C"));
        assertThrows(ConcurrentModificationException.class, c::hasNext);
    }

    @Test void undoRedoPrimitiveRoundTripsThroughRestoreRecord() {
        RID rid = db.insert("items", item(1, "before", 1, "C"));
        RecordImage before = new RecordImage(true, item(1, "before", 1, "C"));
        db.sm.update("items", rid, item(1, "after", 2, "D"), db.next());
        db.sm.restoreRecord("items", rid, before, db.next());
        assertEquals(item(1, "before", 1, "C"), db.sm.fetch("items", rid).orElseThrow().values());
    }

    @Nested
    @DisplayName("Gaps: required by FRM/QP, currently failing")
    class Gaps {
        private Method declared(String name, Class<?>... params) throws Exception {
            return StorageManager.class.getMethod(name, params);
        }

        @Test void overridesLsnBearingCreateTable() throws Exception {
            assertEquals(StorageManager.class, declared("createTable", TableSchema.class, long.class).getDeclaringClass());
        }

        @Test void overridesLsnBearingDropTable() throws Exception {
            assertEquals(StorageManager.class,
                    declared("dropTable", String.class, DropBehavior.class, long.class).getDeclaringClass());
        }

        @Test void overridesRestoreSchema() throws Exception {
            assertEquals(StorageManager.class, declared("restoreSchema", SchemaImage.class, long.class).getDeclaringClass());
        }

        @Test void overridesLsnBearingCreateIndex() throws Exception {
            assertEquals(StorageManager.class, declared("createIndex", IndexMetadata.class, long.class).getDeclaringClass());
        }

        @Test void overridesCreateSnapshot() throws Exception {
            assertEquals(StorageManager.class, declared("createSnapshot").getDeclaringClass());
        }

        @Test void overridesRestoreSnapshot() throws Exception {
            assertEquals(StorageManager.class, declared("restoreSnapshot", String.class).getDeclaringClass());
        }

        @Test void deletedRecordSpaceIsReclaimedByLaterInserts() {
            List<RID> rids = new java.util.ArrayList<>();
            for (int k = 0; k < 40; k++) rids.add(db.insert("items", item(k, String.format("name%03d", k), 1, "C")));
            long pages = db.blocks.getPageCount("t_items");
            for (RID rid : rids.subList(0, 20)) db.sm.delete("items", rid, db.next());
            for (int k = 100; k < 120; k++) db.insert("items", item(k, String.format("name%03d", k), 1, "C")); // same width as the deleted rows
            assertEquals(40, scan(db.sm, "items").size());
            assertEquals(pages, db.blocks.getPageCount("t_items"), "freed slots should be reused, not new pages");
        }

        @Test void restoreRecordKeepsIndexesInSync() {
            db.sm.createIndex(new IndexMetadata("ix", "items", "id", IndexType.BPLUS_TREE));
            RID rid = db.insert("items", item(1, "a", 1, "C"));
            RecordImage before = new RecordImage(true, item(1, "a", 1, "C"));
            db.sm.update("items", rid, item(2, "a", 1, "C"), db.next());
            db.sm.restoreRecord("items", rid, before, db.next()); // undo
            IndexCondition one = new IndexCondition(IndexOperator.EQ, i(1), Optional.empty(), true, true);
            IndexCondition two = new IndexCondition(IndexOperator.EQ, i(2), Optional.empty(), true, true);
            assertEquals(List.of(rid), db.sm.lookupIndex("ix", one), "undo must put the old key back in the index");
            assertTrue(db.sm.lookupIndex("ix", two).isEmpty(), "undo must remove the new key from the index");
        }

        @Test void restoreRecordOfDeletedRowReindexesIt() {
            db.sm.createIndex(new IndexMetadata("ix", "items", "id", IndexType.HASH));
            RID rid = db.insert("items", item(5, "a", 1, "C"));
            db.sm.delete("items", rid, db.next());
            db.sm.restoreRecord("items", rid, new RecordImage(true, item(5, "a", 1, "C")), db.next());
            IndexCondition five = new IndexCondition(IndexOperator.EQ, i(5), Optional.empty(), true, true);
            assertEquals(List.of(rid), db.sm.lookupIndex("ix", five));
        }

        @Test void dropTableDeletesIndexFilesAndAllowsRecreatingSameIndexName() {
            for (int k = 0; k < 5; k++) db.insert("items", item(k, "a", 1, "C"));
            db.sm.createIndex(new IndexMetadata("ix_name", "items", "name", IndexType.HASH));
            db.sm.flushAll();
            long idxBefore = idxFiles();
            assertTrue(idxBefore > 0, "test precondition: an .idx file exists");
            db.sm.dropTable("items", DropBehavior.RESTRICT);
            assertEquals(0, idxFiles(), "no orphan .idx files after DROP TABLE");
            db.sm.createTable(items("t_items", "items"));
            assertDoesNotThrow(() -> db.sm.createIndex(new IndexMetadata("ix_name", "items", "name", IndexType.HASH)),
                    "index name must be reusable after the table was dropped");
        }

        @Test void growingVarcharUpdateOnFullPageSucceeds() {
            TableSchema s = new TableSchema("t_v", "v", List.of(col("k", DataType.INTEGER), col("t", DataType.VARCHAR, 200)));
            db.sm.createTable(s);
            List<RID> rids = new java.util.ArrayList<>();
            for (int k = 0; k < 40; k++) {
                try {
                    rids.add(db.sm.insert(db.sm.reserveInsert("v", List.of(i(k), vc("x"))), db.next()));
                } catch (RuntimeException e) { break; }
            }
            RID first = rids.get(0);
            assertDoesNotThrow(() -> db.sm.update("v", first, List.of(i(0), vc("y".repeat(150))), db.next()),
                    "a record that grows must be relocated/handled, not rejected");
        }

        private long idxFiles() {
            try (var files = Files.list(dir)) {
                return files.filter(p -> p.getFileName().toString().endsWith(".idx")).count();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
