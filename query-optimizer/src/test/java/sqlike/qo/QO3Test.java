package sqlike.qo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.ErrorCode;
import sqlike.shared.JoinAlgorithm;
import sqlike.shared.JoinSpec;
import sqlike.shared.QoException;
import sqlike.shared.JoinType;
import sqlike.shared.PlanNode;

/** QO3 regressions for catalog binding, type compatibility, and cost estimation. */
public final class QO3Test {
    private static int passed;
    private static int failed;

    private QO3Test() { }

    public static int run() {
        passed = 0;
        failed = 0;
        IStorageManager storage = MockStorageManager.create();
        QueryOptimizer optimizer = new QueryOptimizer(storage);
        Binder binder = new Binder(storage);
        CardinalityEstimator estimator = new CardinalityEstimator(storage);

        test("INSERT accepts VARCHAR literal for CHAR column", () ->
                optimizer.optimizeQuery(optimizer.parseQuery("INSERT INTO dept VALUES (30, 'Math')")));
        test("comparison accepts VARCHAR literal for CHAR column", () ->
                optimizer.optimizeQuery(optimizer.parseQuery("SELECT * FROM takes WHERE grade = 'E'")));
        test("numeric and text types remain incompatible", () -> {
            try {
                optimizer.optimizeQuery(optimizer.parseQuery("SELECT * FROM students WHERE name = 5"));
                throw new AssertionError("expected semantic error");
            } catch (QoException error) {
                if (error.getInfo().code() != ErrorCode.SEMANTIC_ERROR) throw error;
            }
        });
        test("three-table NATURAL JOIN remains estimable after rewriting", () -> {
            var parsed = optimizer.parseQuery(
                    "SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes");
            PlanNode bound = binder.bind(parsed);
            PlanNode rewritten = new SelectionProjectionRewriter().rewrite(bound);
            long rows = estimator.estimateRows(rewritten);
            if (rows <= 0) throw new AssertionError("expected positive estimated cardinality, got " + rows);
        });
        test("range predicate estimates half the input rows", () -> {
            PlanNode bound = binder.bind(optimizer.parseQuery("SELECT * FROM students WHERE id > 10"));
            PlanNode filter = bound.children().get(0);
            eq(2500L, estimator.estimateRows(filter));
        });
        test("join output blocks use combined tuple size", () -> {
            PlanNode bound = binder.bind(optimizer.parseQuery(
                    "SELECT * FROM students JOIN dept ON students.dept_id = dept.dept_id"));
            PlanNode join = bound.children().get(0);
            eq(79L, estimator.estimateBlocks(join));
        });
        test("three-table join blocks include both previous inputs", () -> {
            PlanNode bound = binder.bind(optimizer.parseQuery(
                    "SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes"));
            PlanNode join = bound.children().get(0);
            eq(197L, estimator.estimateBlocks(join));
        });
        test("BNLJ charges two seeks per outer batch", () -> {
            JoinSpec cartesian = new JoinSpec(JoinType.CARTESIAN, Optional.empty(),
                    JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
            PlanNode plan = PlanNodes.join(PlanNodes.scan("students"), PlanNodes.scan("dept"), cartesian);
            close(410.0, new CostModel(storage).getCost(plan));
        });
        test("Binder normalizes table names in scans and DML", () -> {
            PlanNode select = binder.bind(optimizer.parseQuery("SELECT * FROM STUDENTS"));
            eq("students", select.children().get(0).scan().orElseThrow().tableName());

            PlanNode insert = binder.bind(optimizer.parseQuery("INSERT INTO STUDENTS VALUES (1, 'Ada', 3.0, 2)"));
            eq("students", insert.mutation().orElseThrow().tableName());
            PlanNode update = binder.bind(optimizer.parseQuery("UPDATE STUDENTS SET GPA = 3.0"));
            eq("students", update.mutation().orElseThrow().tableName());
            eq("students", update.children().get(0).scan().orElseThrow().tableName());
            PlanNode delete = binder.bind(optimizer.parseQuery("DELETE FROM STUDENTS"));
            eq("students", delete.mutation().orElseThrow().tableName());
            eq("students", delete.children().get(0).scan().orElseThrow().tableName());
            PlanNode index = binder.bind(optimizer.parseQuery("CREATE INDEX ix ON STUDENTS(NAME)"));
            eq("students", index.createIndex().orElseThrow().tableName());
        });
        test("statistics are fetched at most once per table per optimization", () -> {
            IStorageManager delegate = MockStorageManager.create();
            Map<String, Integer> calls = new HashMap<>();
            IStorageManager counted = (IStorageManager) Proxy.newProxyInstance(
                    QO3Test.class.getClassLoader(), new Class<?>[] { IStorageManager.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("getStatistics")) {
                            String table = ((String) args[0]).toLowerCase();
                            calls.merge(table, 1, Integer::sum);
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            QueryOptimizer countedOptimizer = new QueryOptimizer(counted);
            countedOptimizer.optimizeQuery(countedOptimizer.parseQuery(
                    "SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes"));
            for (String table : List.of("students", "dept", "takes"))
                eq(1, calls.getOrDefault(table, 0));
        });

        System.out.printf("QO3: %d passed, %d failed%n", passed, failed);
        return failed;
    }

    private static void close(double expected, double actual) {
        if (Math.abs(expected - actual) > 1e-9)
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual))
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }

    private static void test(String name, Runnable action) {
        try {
            action.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failed++;
            System.out.println("FAIL " + name + ": " + error);
        }
    }
}
