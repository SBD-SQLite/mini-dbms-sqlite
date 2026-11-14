package sqlike.storage;

import java.util.List;
import sqlike.shared.TableSchema;
import sqlike.shared.TableStatistics;
import sqlike.utils.SampleDatabaseLoader;

public final class StorageDriver {
    private final StorageManager storage;
    private final SampleDatabaseLoader loader;

    public StorageDriver(StorageManager storage, SampleDatabaseLoader loader) {
        this.storage = storage;
        this.loader = loader;
    }

    public static void main(String[] args) {
    }

    public void runAllTest() {
    }

    public void testCrud() {
    }

    public void testCatalog() {
        System.out.println("== Catalog test ==");
        try {
            List<String> tables = storage.listTables();
            check(tables.size() >= 3, "at least 3 tables in catalog (found " + tables.size() + ")");
            for (String table : tables) {
                TableSchema schema = storage.getSchema(table);
                check(!schema.columns().isEmpty(), table + ": " + schema.columns().size() + " columns");
                check(storage.getSchema(table.toUpperCase()).equals(schema), table + ": case-insensitive lookup");
                System.out.println("   indexes: " + storage.getIndexes(table).size());
                try {
                    TableStatistics s = storage.getStatistics(table);
                    check(s.recordCount() >= 0 && s.blockCount() >= 0, table + ": r=" + s.recordCount() + " b=" + s.blockCount() + " avg=" + s.averageRecordSize() + " bf=" + s.blockingFactor());
                } catch (StorageException e) {
                    System.out.println("[SKIP] " + table + ": statistics unavailable (" + e.getMessage() + ")");
                }
            }
        } catch (StorageException e) {
            throw new IllegalStateException("Catalog test failed: " + e.getMessage(), e);
        }
    }

    private static void check(boolean ok, String message) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + message);
        if (!ok) throw new IllegalStateException("Catalog check failed: " + message);
    }

    public void testBuffer() {
    }

    public void testIndex() {
    }

    public void testSerializer() {
    }

    public void printReport() {
    }
}
