package sqlike;

import static org.junit.jupiter.api.Assertions.*;
import static sqlike.Fixtures.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sqlike.shared.DataType;
import sqlike.shared.DropBehavior;
import sqlike.shared.ForeignKeySpec;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexType;
import sqlike.shared.InsertReservation;
import sqlike.shared.TableSchema;
import sqlike.storage.StorageErrorCode;

/** StorageManager facade: DDL, foreign keys, drop/clear, lifecycle (SM-15 and DDL requirements). */
class StorageManagerTest {
    @TempDir Path dir;
    Db db;

    @BeforeEach void setUp() { db = new Db(dir, 512, 32); }

    @AfterEach void tearDown() {
        try { db.sm.close(); } catch (RuntimeException ignored) { }
    }

    TableSchema parent() { return items("t_parent", "parent"); }

    TableSchema child(String name, String id) {
        return new TableSchema(id, name, List.of(col("cid", DataType.INTEGER), col("pid", DataType.INTEGER)),
                List.of("cid"), List.of(new ForeignKeySpec(List.of("pid"), "parent", List.of("id"))));
    }

    @Test void createTableRegistersSchemaAndPersistsCatalog() {
        db.sm.createTable(parent());
        assertEquals(List.of("parent"), db.sm.listTables());
        assertEquals(parent(), db.sm.getSchema("PARENT"));
        assertTrue(Files.exists(dir.resolve("catalog.bin")));
        db.sm.close();
        try (Db again = new Db(dir, 512, 32)) {
            assertEquals(parent(), again.sm.getSchema("parent"));
        }
    }

    @Test void createTableWithoutTableIdGeneratesOne() {
        db.sm.createTable(new TableSchema("", "Things", List.of(col("a", DataType.INTEGER)), List.of(), List.of()));
        String id = db.sm.getSchema("things").tableId();
        assertTrue(id.matches("[A-Za-z0-9_-]+") && !id.isBlank(), id);
        db.insert("things", List.of(i(1)));
        assertEquals(1, scan(db.sm, "things").size());
    }

    @Test void createTableRejectsDuplicatesNullAndUnsafeIds() {
        db.sm.createTable(parent());
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.createTable(parent())));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.createTable(items("t_x", "PARENT"))));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.createTable((TableSchema) null)));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.createTable(items("../bad", "bad"))));
        assertEquals(List.of("parent"), db.sm.listTables(), "failed creates must leave no trace");
    }

    @Test void createTableChecksForeignKeys() {
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> db.sm.createTable(child("child", "t_child"))));
        db.sm.createTable(parent());
        assertDoesNotThrow(() -> db.sm.createTable(child("child", "t_child")));
        assertEquals(List.of("child"), db.sm.getReferencingTables("parent").stream().map(TableSchema::tableName).toList());
    }

    @Test void dropRestrictRefusedWhileReferencedThenAllowed() {
        db.sm.createTable(parent());
        db.sm.createTable(child("child", "t_child"));
        assertEquals(StorageErrorCode.INVALID_STATE, codeOf(() -> db.sm.dropTable("parent", DropBehavior.RESTRICT)));
        assertTrue(db.sm.listTables().contains("parent"));
        db.sm.dropTable("child", DropBehavior.RESTRICT);
        db.sm.dropTable("parent", DropBehavior.RESTRICT);
        assertTrue(db.sm.listTables().isEmpty());
    }

    @Test void dropCascadeRemovesForeignKeysOfDependents() {
        db.sm.createTable(parent());
        db.sm.createTable(child("child", "t_child"));
        db.sm.dropTable("parent", DropBehavior.CASCADE);
        assertEquals(List.of("child"), db.sm.listTables());
        assertTrue(db.sm.getSchema("child").foreignKeys().isEmpty());
    }

    @Test void dropTableDeletesDataFileAndRejectsLaterUse() {
        db.sm.createTable(parent());
        db.insert("parent", item(1, "a", 1, "A"));
        db.sm.flushAll();
        assertTrue(Files.exists(dir.resolve("t_parent.tbl")));
        db.sm.dropTable("parent", DropBehavior.RESTRICT);
        assertFalse(Files.exists(dir.resolve("t_parent.tbl")));
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> scan(db.sm, "parent")));
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> db.sm.getSchema("parent")));
    }

    @Test void recreatedTableStartsEmpty() {
        db.sm.createTable(parent());
        db.insert("parent", item(1, "a", 1, "A"));
        db.sm.dropTable("parent", DropBehavior.RESTRICT);
        db.sm.createTable(parent());
        assertTrue(scan(db.sm, "parent").isEmpty());
        assertEquals(0, db.sm.getStatistics("parent").recordCount());
    }

    @Test void dropValidation() {
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> db.sm.dropTable("ghost", DropBehavior.RESTRICT)));
        db.sm.createTable(parent());
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.dropTable("parent", null)));
    }

    @Test void clearTableRemovesAllRowsAndReturnsCount() {
        db.sm.createTable(parent());
        for (int k = 0; k < 25; k++) db.insert("parent", item(k, "n", 1, "A"));
        assertEquals(25, db.sm.clearTable("parent"));
        assertTrue(scan(db.sm, "parent").isEmpty());
        assertEquals(0, db.sm.clearTable("parent"));
        db.insert("parent", item(1, "again", 1, "A"));
        assertEquals(1, scan(db.sm, "parent").size());
    }

    @Test void clearTableRefusedWithActiveReservationOrCursor() {
        db.sm.createTable(parent());
        InsertReservation r = db.sm.reserveInsert("parent", item(1, "a", 1, "A"));
        assertEquals(StorageErrorCode.INVALID_STATE, codeOf(() -> db.sm.clearTable("parent")));
        db.sm.releaseReservation(r);
        var cursor = db.sm.scan("parent");
        assertEquals(StorageErrorCode.INVALID_STATE, codeOf(() -> db.sm.clearTable("parent")));
        cursor.close();
        assertDoesNotThrow(() -> db.sm.clearTable("parent"));
    }

    @Test void unknownTableErrorsAreConsistent() {
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> db.sm.scan("ghost")));
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> db.sm.getIndexes("ghost")));
        assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> db.sm.clearTable("ghost")));
        assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> db.sm.scan(" ")));
    }

    @Test void listTablesIsSortedCaseInsensitive() {
        db.sm.createTable(items("t_b", "banana"));
        db.sm.createTable(items("t_a", "Apple"));
        db.sm.createTable(items("t_c", "cherry"));
        assertEquals(List.of("Apple", "banana", "cherry"), db.sm.listTables());
    }

    @Test void flushAllPersistsDataWithoutClose() {
        db.sm.createTable(parent());
        db.insert("parent", item(1, "a", 1, "A"));
        db.sm.flushAll();
        assertTrue(Files.exists(dir.resolve("t_parent.tbl")));
        assertTrue(db.blocks.getPageCount("t_parent") >= 1);
        assertTrue(db.buffer.getDirtyPageIds().isEmpty());
    }

    @Test void closeThenReopenKeepsEverythingIncludingIndexes() {
        db.sm.createTable(parent());
        for (int k = 0; k < 10; k++) db.insert("parent", item(k, "n" + k, k, "A"));
        db.sm.createIndex(new IndexMetadata("ix", "parent", "id", IndexType.BPLUS_TREE));
        db.sm.close();
        try (Db again = new Db(dir, 512, 32)) {
            assertEquals(10, scan(again.sm, "parent").size());
            assertEquals(1, again.sm.getIndexes("parent").size());
        }
    }

    @Test void rowsOfDifferentTablesDoNotInterfere() {
        db.sm.createTable(items("t_a", "a"));
        db.sm.createTable(items("t_b", "b"));
        db.insert("a", item(1, "x", 1, "A"));
        db.insert("b", item(2, "y", 1, "B"));
        assertEquals(item(1, "x", 1, "A"), scan(db.sm, "a").get(0).values());
        assertEquals(item(2, "y", 1, "B"), scan(db.sm, "b").get(0).values());
    }
}
