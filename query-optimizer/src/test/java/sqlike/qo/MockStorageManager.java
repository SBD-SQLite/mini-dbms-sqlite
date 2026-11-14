package sqlike.qo;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

/** catalog & statistics jadi Query Optimizer bisa run tanpaa Storage Manager yg asli */
final class MockStorageManager {
    private MockStorageManager() { }

    static IStorageManager create() {
        Map<String, TableSchema> schemas = new LinkedHashMap<>();
        Map<String, TableStatistics> stats = new LinkedHashMap<>();
        add(schemas, stats, "students", List.of(
                col("id", DataType.INTEGER, null), col("name", DataType.VARCHAR, 20),
                col("gpa", DataType.FLOAT, null), col("dept_id", DataType.INTEGER, null)),
                new TableStatistics(5000, 50, 40, 102, Map.of("id", 5000L, "name", 4800L, "gpa", 400L, "dept_id", 20L)));
        add(schemas, stats, "dept", List.of(
                col("dept_id", DataType.INTEGER, null), col("dname", DataType.CHAR, 10)),
                new TableStatistics(20, 1, 24, 170, Map.of("dept_id", 20L, "dname", 20L)));
        add(schemas, stats, "takes", List.of(
                col("id", DataType.INTEGER, null), col("course_id", DataType.INTEGER, null),
                col("grade", DataType.CHAR, 2)),
                new TableStatistics(10000, 40, 16, 256, Map.of("id", 4500L, "course_id", 200L, "grade", 10L)));
        return (IStorageManager) Proxy.newProxyInstance(MockStorageManager.class.getClassLoader(),
                new Class<?>[] { IStorageManager.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "listTables" -> new ArrayList<>(schemas.keySet());
                    case "getSchema" -> schemas.get(((String) args[0]).toLowerCase());
                    case "getStatistics" -> stats.get(((String) args[0]).toLowerCase());
                    case "getIndexes" -> List.<IndexMetadata>of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void add(Map<String, TableSchema> schemas, Map<String, TableStatistics> stats, String name,
                            List<ColumnSchema> columns, TableStatistics statistics) {
        schemas.put(name, new TableSchema("t_" + name, name, columns));
        stats.put(name, statistics);
    }

    private static ColumnSchema col(String name, DataType type, Integer maxLength) {
        return new ColumnSchema(name, type, Optional.ofNullable(maxLength));
    }
}
