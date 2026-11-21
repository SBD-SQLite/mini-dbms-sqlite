package sqlike;

import static org.junit.jupiter.api.Assertions.*;
import static sqlike.Fixtures.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sqlike.catalog.CatalogManager;
import sqlike.shared.DataType;
import sqlike.shared.ForeignKeySpec;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexType;
import sqlike.shared.TableSchema;
import sqlike.shared.TableStatistics;
import sqlike.storage.StorageErrorCode;

/** Catalog Manager (SM-07, SM-08) and Statistics Manager (SM-09). */
class CatalogAndStatisticsTest {
    @TempDir Path dir;

    @Nested
    @DisplayName("CatalogManager")
    class Catalog {
        CatalogManager cat;

        @BeforeEach void init() { cat = new CatalogManager(dir.resolve("catalog.bin")); }

        @Test void registerAndLookupAreCaseInsensitive() {
            cat.registerTable(items("t_items", "Items"));
            assertTrue(cat.tableExists("ITEMS"));
            assertEquals("Items", cat.getSchema("items").tableName());
            assertEquals(List.of("Items"), cat.listTables());
        }

        @Test void duplicateNameOrTableIdIsRejected() {
            cat.registerTable(items("t_items", "items"));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> cat.registerTable(items("t_other", "ITEMS"))));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> cat.registerTable(items("t_items", "other"))));
        }

        @Test void unknownTableThrowsTableNotFound() {
            assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> cat.getSchema("nope")));
        }

        @Test void generateTableIdIsUniqueAndFileSafe() {
            String first = cat.generateTableId("My Table!");
            assertTrue(first.matches("[A-Za-z0-9_-]+"), first);
            cat.registerTable(new TableSchema(first, "My Table!", List.of(col("a", DataType.INTEGER))));
            assertNotEquals(first, cat.generateTableId("My Table!"));
        }

        @Test void indexRegistrationRequiresExistingTableAndColumn() {
            cat.registerTable(items("t_items", "items"));
            cat.registerIndex(new IndexMetadata("ix", "items", "id", IndexType.HASH));
            assertEquals(1, cat.getIndexes("items").size());
            assertEquals(StorageErrorCode.INVALID_VALUE,
                    codeOf(() -> cat.registerIndex(new IndexMetadata("IX", "items", "id", IndexType.HASH))));
            assertNotNull(codeOf(() -> cat.registerIndex(new IndexMetadata("iy", "ghost", "id", IndexType.HASH))));
        }

        @Test void saveAndLoadRoundTripKeepsSchemasIndexesKeys() {
            TableSchema parent = items("t_parent", "parent");
            TableSchema child = new TableSchema("t_child", "child",
                    List.of(col("cid", DataType.INTEGER), col("pid", DataType.INTEGER)),
                    List.of("cid"), List.of(new ForeignKeySpec(List.of("pid"), "parent", List.of("id"))));
            cat.registerTable(parent);
            cat.registerTable(child);
            cat.registerIndex(new IndexMetadata("ix_name", "parent", "name", IndexType.BPLUS_TREE));
            cat.save();

            CatalogManager again = new CatalogManager(dir.resolve("catalog.bin"));
            again.load();
            assertEquals(parent, again.getSchema("parent"));
            assertEquals(child, again.getSchema("child"));
            assertEquals(List.of(new IndexMetadata("ix_name", "parent", "name", IndexType.BPLUS_TREE)),
                    again.getIndexes("parent"));
        }

        @Test void loadWithoutFileGivesEmptyCatalog() {
            CatalogManager empty = new CatalogManager(dir.resolve("none.bin"));
            empty.load();
            assertTrue(empty.listTables().isEmpty());
        }

        @Test void foreignKeyValidation() {
            cat.registerTable(items("t_parent", "parent"));
            TableSchema ok = fk("t_c1", "c1", "id", DataType.INTEGER, "parent", "id");
            assertDoesNotThrow(() -> cat.checkForeignKeyTargets(ok));
            TableSchema missingTable = fk("t_c2", "c2", "id", DataType.INTEGER, "ghost", "id");
            assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> cat.checkForeignKeyTargets(missingTable)));
            TableSchema badColumn = fk("t_c3", "c3", "id", DataType.INTEGER, "parent", "zzz");
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> cat.checkForeignKeyTargets(badColumn)));
            TableSchema badType = fk("t_c4", "c4", "id", DataType.VARCHAR, "parent", "id");
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> cat.checkForeignKeyTargets(badType)));
            TableSchema notPk = fk("t_c5", "c5", "id", DataType.VARCHAR, "parent", "name");
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> cat.checkForeignKeyTargets(notPk)));
        }

        @Test void dependentsExcludeSelfReferences() {
            cat.registerTable(items("t_parent", "parent"));
            cat.registerTable(fk("t_child", "child", "pid", DataType.INTEGER, "parent", "id"));
            assertEquals(List.of("child"), cat.dependents("parent").stream().map(TableSchema::tableName).toList());
            assertTrue(cat.dependents("child").isEmpty());
        }

        @Test void snapshotAndRestoreAreIsolatedCopies() {
            cat.registerTable(items("t_items", "items"));
            Map<String, TableSchema> saved = cat.snapshotSchemas();
            cat.unregisterTable("items");
            assertFalse(cat.tableExists("items"));
            cat.restore(saved, cat.snapshotIndexes());
            assertTrue(cat.tableExists("items"));
        }

        @Test void unregisterTableRemovesItsIndexesToo() {
            cat.registerTable(items("t_items", "items"));
            cat.registerIndex(new IndexMetadata("ix", "items", "id", IndexType.HASH));
            cat.unregisterTable("items");
            assertTrue(cat.getIndexes().isEmpty());
        }

        @Test void replaceSchemaCannotChangeTableId() {
            cat.registerTable(items("t_items", "items"));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> cat.replaceSchema(items("OTHER", "items"))));
        }

        private TableSchema fk(String id, String name, String col, DataType type, String refTable, String refCol) {
            var column = type == DataType.VARCHAR ? col(col, type, 10) : col(col, type);
            return new TableSchema(id, name, List.of(column),
                    List.of(), List.of(new ForeignKeySpec(List.of(col), refTable, List.of(refCol))));
        }
    }

    @Nested
    @DisplayName("StatisticsManager")
    class Statistics {
        @Test void emptyTableHasZeroStatistics() {
            try (Db db = new Db(dir)) {
                db.sm.createTable(items("t_items", "items"));
                TableStatistics s = db.sm.getStatistics("items");
                assertEquals(0, s.recordCount());
                assertEquals(0, s.blockCount());
                assertEquals(0, s.blockingFactor());
            }
        }

        @Test void computesNrBrFrAvgSizeAndDistinctCounts() {
            try (Db db = new Db(dir, 256, 16)) {
                db.sm.createTable(items("t_items", "items"));
                for (int k = 0; k < 30; k++) db.insert("items", item(k, "n" + (k % 5), 1.5, "C" + (k % 3)));
                TableStatistics s = db.sm.getStatistics("items");
                assertEquals(30, s.recordCount());
                assertTrue(s.blockCount() > 1, "30 rows do not fit one 256-byte page");
                assertEquals(db.blocks.getPageCount("t_items"), s.blockCount());
                assertTrue(s.averageRecordSize() > 0);
                assertTrue(s.blockingFactor() >= 1);
                assertEquals(30L, s.distinctCounts().get("id"));
                assertEquals(5L, s.distinctCounts().get("name"));
                assertEquals(1L, s.distinctCounts().get("price"));
                assertEquals(3L, s.distinctCounts().get("code"));
                assertTrue(s.blockCount() * s.blockingFactor() >= s.recordCount());
            }
        }

        @Test void statisticsAreInvalidatedByEveryMutation() {
            try (Db db = new Db(dir)) {
                db.sm.createTable(items("t_items", "items"));
                var rid = db.insert("items", item(1, "a", 1, "A"));
                assertEquals(1, db.sm.getStatistics("items").recordCount());
                db.insert("items", item(2, "b", 1, "B"));
                assertEquals(2, db.sm.getStatistics("items").recordCount());
                db.sm.update("items", rid, item(1, "zzz", 9, "Z"), db.next());
                assertEquals(2, db.sm.getStatistics("items").distinctCounts().get("name"));
                db.sm.delete("items", rid, db.next());
                assertEquals(1, db.sm.getStatistics("items").recordCount());
                db.sm.clearTable("items");
                assertEquals(0, db.sm.getStatistics("items").recordCount());
            }
        }

        @Test void unknownTableThrows() {
            try (Db db = new Db(dir)) {
                assertEquals(StorageErrorCode.TABLE_NOT_FOUND, codeOf(() -> db.sm.getStatistics("ghost")));
            }
        }
    }
}
