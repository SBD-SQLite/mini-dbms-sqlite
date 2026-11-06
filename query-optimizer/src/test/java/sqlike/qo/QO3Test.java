package sqlike.qo;

import sqlike.contract.IStorageManager;
import sqlike.shared.ErrorCode;
import sqlike.shared.PlanNode;

/** QO3 regressions for catalog binding, type compatibility, and cardinality. */
public final class QO3Test {
    private static int passed;
    private static int failed;

    private QO3Test() { }

    public static int run() {
        passed = 0;
        failed = 0;
        IStorageManager storage = MockStorageManager.create();
        QueryOptimizer optimizer = new QueryOptimizer(storage);

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
            PlanNode bound = new Binder(storage).bind(parsed);
            PlanNode rewritten = new SelectionProjectionRewriter().rewrite(bound);
            long rows = new CardinalityEstimator(storage).estimateRows(rewritten);
            if (rows <= 0) throw new AssertionError("expected positive estimated cardinality, got " + rows);
        });

        System.out.printf("QO3: %d passed, %d failed%n", passed, failed);
        return failed;
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
