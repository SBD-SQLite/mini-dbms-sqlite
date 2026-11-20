package sqlike;

import static org.junit.jupiter.api.Assertions.*;
import static sqlike.Fixtures.*;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexOperator;
import sqlike.shared.IndexStatistics;
import sqlike.shared.IndexType;
import sqlike.shared.RID;
import sqlike.shared.Value;
import sqlike.storage.StorageErrorCode;

/** Hash index and B+Tree index, lookup operators, maintenance, persistence (SM-13, SM-14; bonus QP-15/QO-17 index scan). */
class IndexTest {
    @TempDir Path dir;
    Db db;
    final IndexMetadata hashName = new IndexMetadata("ix_hash_name", "items", "name", IndexType.HASH);
    final IndexMetadata treeId = new IndexMetadata("ix_tree_id", "items", "id", IndexType.BPLUS_TREE);
    final IndexMetadata treePrice = new IndexMetadata("ix_tree_price", "items", "price", IndexType.BPLUS_TREE);

    @BeforeEach void setUp() {
        db = new Db(dir, 512, 64);
        db.sm.createTable(items("t_items", "items"));
    }

    @AfterEach void tearDown() {
        try { db.sm.close(); } catch (RuntimeException ignored) { }
    }

    static IndexCondition eq(Value v) { return new IndexCondition(IndexOperator.EQ, v, Optional.empty(), true, true); }
    static IndexCondition op(IndexOperator o, Value v) { return new IndexCondition(o, v, Optional.empty(), true, true); }
    static IndexCondition between(Value lo, Value hi, boolean li, boolean ui) {
        return new IndexCondition(IndexOperator.BETWEEN, lo, Optional.of(hi), li, ui);
    }

    /** Inserts ids 0..n-1; name = "n" + id%7; price = id. Returns RIDs indexed by id. */
    RID[] load(int n) {
        RID[] rids = new RID[n];
        for (int k = 0; k < n; k++) rids[k] = db.insert("items", item(k, "n" + (k % 7), k, "C"));
        return rids;
    }

    Set<Integer> ids(List<RID> rids) {
        return rids.stream().map(r -> db.sm.fetch("items", r).orElseThrow().values().get(0).integerValue().orElseThrow())
                .collect(Collectors.toSet());
    }

    Set<Integer> range(int from, int toInclusive) {
        Set<Integer> s = new HashSet<>();
        for (int k = from; k <= toInclusive; k++) s.add(k);
        return s;
    }

    @Test void hashIndexEqualityLookup() {
        load(50);
        db.sm.createIndex(hashName);
        List<RID> hits = db.sm.lookupIndex("ix_hash_name", eq(vc("n3")));
        assertEquals(Set.of(3, 10, 17, 24, 31, 38, 45), ids(hits));
        assertTrue(db.sm.lookupIndex("ix_hash_name", eq(vc("absent"))).isEmpty());
    }

    @Test void hashIndexRejectsRangeOperators() {
        load(5);
        db.sm.createIndex(hashName);
        assertEquals(StorageErrorCode.UNSUPPORTED_OPERATION,
                codeOf(() -> db.sm.lookupIndex("ix_hash_name", op(IndexOperator.GT, vc("n1")))));
    }

    @Test void bplusEqualityAndAllRangeOperators() {
        load(100);
        db.sm.createIndex(treeId);
        assertEquals(Set.of(42), ids(db.sm.lookupIndex("ix_tree_id", eq(i(42)))));
        assertEquals(range(51, 99), ids(db.sm.lookupIndex("ix_tree_id", op(IndexOperator.GT, i(50)))));
        assertEquals(range(50, 99), ids(db.sm.lookupIndex("ix_tree_id", op(IndexOperator.GE, i(50)))));
        assertEquals(range(0, 49), ids(db.sm.lookupIndex("ix_tree_id", op(IndexOperator.LT, i(50)))));
        assertEquals(range(0, 50), ids(db.sm.lookupIndex("ix_tree_id", op(IndexOperator.LE, i(50)))));
    }

    @Test void bplusBetweenHonoursInclusiveFlags() {
        load(100);
        db.sm.createIndex(treeId);
        assertEquals(range(10, 20), ids(db.sm.lookupIndex("ix_tree_id", between(i(10), i(20), true, true))));
        assertEquals(range(11, 19), ids(db.sm.lookupIndex("ix_tree_id", between(i(10), i(20), false, false))));
        assertEquals(range(10, 19), ids(db.sm.lookupIndex("ix_tree_id", between(i(10), i(20), true, false))));
        assertEquals(range(11, 20), ids(db.sm.lookupIndex("ix_tree_id", between(i(10), i(20), false, true))));
        assertEquals(Set.of(15), ids(db.sm.lookupIndex("ix_tree_id", between(i(15), i(15), true, true))));
        assertTrue(db.sm.lookupIndex("ix_tree_id", between(i(15), i(15), false, true)).isEmpty());
        assertEquals(StorageErrorCode.INVALID_VALUE,
                codeOf(() -> db.sm.lookupIndex("ix_tree_id", between(i(20), i(10), true, true))));
    }

    @Test void bplusOnFloatColumnAndNegativeAndBoundaryKeys() {
        db.sm.createIndex(treePrice);
        for (double p : new double[] {-5.5, -0.0, 0.0, 2.25, 100.0}) db.insert("items", item((int) p + 1000, "x", p, "C"));
        assertEquals(2, db.sm.lookupIndex("ix_tree_price", between(f(-1), f(1), true, true)).size());
        assertEquals(1, db.sm.lookupIndex("ix_tree_price", op(IndexOperator.LT, f(-1))).size());
        assertEquals(1, db.sm.lookupIndex("ix_tree_price", op(IndexOperator.GT, f(50))).size());
    }

    @Test void bplusSplitsAcrossManyKeysAndStaysCorrect() {
        db.sm.createIndex(treeId);
        for (int k = 0; k < 400; k++) db.insert("items", item((k * 37) % 400, "n", 1, "C")); // permuted insert order
        IndexStatistics s = db.sm.getIndexStatistics("ix_tree_id");
        assertTrue(s.height().orElse(0) >= 2, "400 keys in 512-byte pages must split the root");
        assertTrue(s.leafPageCount().orElse(0) > 1);
        assertEquals(range(100, 299), ids(db.sm.lookupIndex("ix_tree_id", between(i(100), i(299), true, true))));
    }

    @Test void duplicateKeysAreAllReturned() {
        db.sm.createIndex(treeId);
        for (int k = 0; k < 30; k++) db.insert("items", item(5, "dup" + k, 1, "C"));
        assertEquals(30, db.sm.lookupIndex("ix_tree_id", eq(i(5))).size());
    }

    @Test void indexIsBuiltFromExistingRowsOnCreate() {
        load(20);
        db.sm.createIndex(treeId);
        assertEquals(range(0, 19), ids(db.sm.lookupIndex("ix_tree_id", op(IndexOperator.GE, i(0)))));
    }

    @Test void insertUpdateDeleteKeepIndexInSync() {
        db.sm.createIndex(treeId);
        db.sm.createIndex(hashName);
        RID rid = db.insert("items", item(1, "alpha", 1, "C"));
        assertEquals(1, db.sm.lookupIndex("ix_tree_id", eq(i(1))).size());
        db.sm.update("items", rid, item(2, "beta", 1, "C"), db.next());
        assertTrue(db.sm.lookupIndex("ix_tree_id", eq(i(1))).isEmpty());
        assertEquals(1, db.sm.lookupIndex("ix_tree_id", eq(i(2))).size());
        assertTrue(db.sm.lookupIndex("ix_hash_name", eq(vc("alpha"))).isEmpty());
        assertEquals(1, db.sm.lookupIndex("ix_hash_name", eq(vc("beta"))).size());
        db.sm.delete("items", rid, db.next());
        assertTrue(db.sm.lookupIndex("ix_tree_id", eq(i(2))).isEmpty());
        assertTrue(db.sm.lookupIndex("ix_hash_name", eq(vc("beta"))).isEmpty());
    }

    @Test void failedInsertRollsBackTheRecordAndIndexes() {
        db.sm.createIndex(treeId);
        // violate the index key type through a reservation with valid schema values but break index by closing? use bad LSN instead
        var r = db.sm.reserveInsert("items", item(1, "a", 1, "C"));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.insert(r, 0)));
        assertTrue(scan(db.sm, "items").isEmpty());
        assertTrue(db.sm.lookupIndex("ix_tree_id", eq(i(1))).isEmpty());
    }

    @Test void clearTableEmptiesIndexesToo() {
        load(10);
        db.sm.createIndex(treeId);
        assertEquals(10, db.sm.clearTable("items"));
        assertTrue(db.sm.lookupIndex("ix_tree_id", op(IndexOperator.GE, i(0))).isEmpty());
        db.insert("items", item(3, "n", 1, "C"));
        assertEquals(1, db.sm.lookupIndex("ix_tree_id", eq(i(3))).size());
    }

    @Test void indexesAreReloadedAfterRestart() {
        load(30);
        db.sm.createIndex(treeId);
        db.sm.createIndex(hashName);
        db.sm.close();
        try (Db again = new Db(dir, 512, 64)) {
            assertEquals(2, again.sm.getIndexes("items").size());
            assertEquals(1, again.sm.lookupIndex("ix_tree_id", eq(i(7))).size());
            assertEquals(5, again.sm.lookupIndex("ix_hash_name", eq(vc("n1"))).size());
        }
    }

    @Test void createIndexValidation() {
        db.sm.createIndex(treeId);
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.createIndex(treeId)));
        assertEquals(StorageErrorCode.INVALID_VALUE,
                codeOf(() -> db.sm.createIndex(new IndexMetadata("IX_TREE_ID", "items", "name", IndexType.HASH))));
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND,
                codeOf(() -> db.sm.createIndex(new IndexMetadata("g", "ghost", "id", IndexType.HASH))));
        assertEquals(StorageErrorCode.INVALID_VALUE,
                codeOf(() -> db.sm.createIndex(new IndexMetadata("g", "items", "nocol", IndexType.HASH))));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.createIndex(null)));
        assertEquals(StorageErrorCode.INVALID_VALUE,
                codeOf(() -> db.sm.createIndex(new IndexMetadata(" ", "items", "id", IndexType.HASH))));
    }

    @Test void lookupValidation() {
        load(3);
        db.sm.createIndex(treeId);
        assertEquals(StorageErrorCode.INDEX_NOT_FOUND, codeOf(() -> db.sm.lookupIndex("nope", eq(i(1)))));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.lookupIndex("ix_tree_id", eq(vc("wrongtype")))));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.lookupIndex("ix_tree_id", null)));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.lookupIndex(" ", eq(i(1)))));
    }

    @Test void indexNamesAreCaseInsensitiveForLookup() {
        load(3);
        db.sm.createIndex(treeId);
        assertEquals(1, db.sm.lookupIndex("IX_TREE_ID", eq(i(1))).size());
    }

    @Test void indexStatisticsExposeStructureForCostModel() {
        load(200);
        db.sm.createIndex(treeId);
        db.sm.createIndex(hashName);
        IndexStatistics tree = db.sm.getIndexStatistics("ix_tree_id");
        assertTrue(tree.height().isPresent());
        assertTrue(tree.leafPageCount().isPresent());
        assertFalse(tree.bucketCount().isPresent(), "a B+Tree has no buckets");
        IndexStatistics hash = db.sm.getIndexStatistics("ix_hash_name");
        assertTrue(hash.bucketCount().isPresent() && hash.bucketCount().getAsInt() > 0);
        assertFalse(hash.height().isPresent(), "a hash index has no height");
        assertEquals(StorageErrorCode.INDEX_NOT_FOUND, codeOf(() -> db.sm.getIndexStatistics("nope")));
    }

    @Test void getIndexesListsPerTableSortedByName() {
        db.sm.createTable(items("t_o", "other"));
        db.sm.createIndex(treeId);
        db.sm.createIndex(hashName);
        db.sm.createIndex(new IndexMetadata("a_other", "other", "id", IndexType.HASH));
        assertEquals(List.of("ix_hash_name", "ix_tree_id"),
                db.sm.getIndexes("items").stream().map(IndexMetadata::indexName).toList());
        assertEquals(1, db.sm.getIndexes("other").size());
    }
}
