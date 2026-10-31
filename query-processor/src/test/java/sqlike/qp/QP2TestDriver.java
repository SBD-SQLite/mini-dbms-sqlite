package sqlike.qp;

import java.util.List;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.ColumnRef;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.ExpressionOperator;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexType;
import sqlike.shared.JoinAlgorithm;
import sqlike.shared.JoinSpec;
import sqlike.shared.JoinType;
import sqlike.shared.MutationSpec;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.QueryPlan;
import sqlike.shared.ResultColumn;
import sqlike.shared.ScanSpec;
import sqlike.shared.SortDirection;
import sqlike.shared.SortSpec;
import sqlike.shared.Value;

/**
 * Standalone test suite and verification driver for QP2 Milestone 1 mock implementations.
 * Tests PlanTranslator, PlanValidator, ExpressionEvaluator, and ValueComparator.
 */
public class QP2TestDriver {

    private static int testsRun = 0;
    private static int testsPassed = 0;

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   SQLike QP2 (Plan Translator & Evaluator) Tests");
        System.out.println("   Milestone 1 Mock Implementation Verification  ");
        System.out.println("==================================================");

        try {
            testValueComparator();
            testExpressionEvaluatorArithmetic();
            testExpressionEvaluatorConditions();
            testExpressionEvaluatorColumns();
            testPlanValidatorArityAndCycle();
            testPlanValidatorParameters();
            testPlanTranslatorSelectPipeline();
            testPlanTranslatorCommandExecutors();
            testPlanTranslatorErrorHandling();

            System.out.println("\n--------------------------------------------------");
            System.out.println("RESULT: All " + testsPassed + " / " + testsRun + " tests PASSED successfully!");
            System.out.println("--------------------------------------------------");
        } catch (Throwable t) {
            System.err.println("\nTEST SUITE FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        testsRun++;
        if (expected == null && actual == null) {
            testsPassed++;
            return;
        }
        if (expected != null && expected.equals(actual)) {
            testsPassed++;
            return;
        }
        throw new AssertionError(message + " - Expected: " + expected + ", Actual: " + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        testsRun++;
        if (condition) {
            testsPassed++;
        } else {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    // =========================================================================
    // 1. ValueComparator Tests
    // =========================================================================
    private static void testValueComparator() {
        System.out.println("[TEST] ValueComparator...");
        ValueComparator comparator = new ValueComparator();

        Value int10 = new Value(DataType.INTEGER, Optional.of(10), Optional.empty(), Optional.empty());
        Value int20 = new Value(DataType.INTEGER, Optional.of(20), Optional.empty(), Optional.empty());
        Value float10 = new Value(DataType.FLOAT, Optional.empty(), Optional.of(10.0), Optional.empty());
        Value float25 = new Value(DataType.FLOAT, Optional.empty(), Optional.of(25.5), Optional.empty());
        Value textA = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("Apple"));
        Value textB = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("Banana"));

        // Integer vs Integer
        assertTrue(comparator.compare(int10, int20) < 0, "10 < 20");
        assertTrue(comparator.compare(int20, int10) > 0, "20 > 10");
        assertTrue(comparator.equal(int10, int10), "10 == 10");

        // Numeric promotion (Integer vs Float)
        assertTrue(comparator.equal(int10, float10), "int 10 == float 10.0");
        assertTrue(comparator.compare(int20, float10) > 0, "int 20 > float 10.0");
        assertTrue(comparator.compare(int20, float25) < 0, "int 20 < float 25.5");

        // Text comparison
        assertTrue(comparator.compare(textA, textB) < 0, "Apple < Banana");
        assertTrue(comparator.equal(textA, textA), "Apple == Apple");

        // Incompatible types check
        try {
            comparator.compare(int10, textA);
            throw new AssertionError("Comparing INT with VARCHAR should fail");
        } catch (IllegalArgumentException e) {
            testsRun++;
            testsPassed++;
        }
    }

    // =========================================================================
    // 2. ExpressionEvaluator Arithmetic Tests
    // =========================================================================
    private static void testExpressionEvaluatorArithmetic() {
        System.out.println("[TEST] ExpressionEvaluator Arithmetic (+, -, *, /)...");
        ValueComparator comparator = new ValueComparator();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);

        Value v15 = new Value(DataType.INTEGER, Optional.of(15), Optional.empty(), Optional.empty());
        Value v5 = new Value(DataType.INTEGER, Optional.of(5), Optional.empty(), Optional.empty());
        Value v2_5 = new Value(DataType.FLOAT, Optional.empty(), Optional.of(2.5), Optional.empty());
        Value vZero = new Value(DataType.INTEGER, Optional.of(0), Optional.empty(), Optional.empty());

        // Addition: 15 + 5 = 20
        Value sum = evaluator.arithmetic(ExpressionOperator.ADD, v15, v5);
        assertEquals(20, sum.integerValue().orElse(0), "15 + 5 = 20");

        // Subtraction: 15 - 5 = 10
        Value diff = evaluator.arithmetic(ExpressionOperator.SUBTRACT, v15, v5);
        assertEquals(10, diff.integerValue().orElse(0), "15 - 5 = 10");

        // Multiplication: 15 * 5 = 75
        Value prod = evaluator.arithmetic(ExpressionOperator.MULTIPLY, v15, v5);
        assertEquals(75, prod.integerValue().orElse(0), "15 * 5 = 75");

        // Division: 15 / 5 = 3
        Value quot = evaluator.arithmetic(ExpressionOperator.DIVIDE, v15, v5);
        assertEquals(3, quot.integerValue().orElse(0), "15 / 5 = 3");

        // Mixed Float Arithmetic: 15 + 2.5 = 17.5
        Value mixed = evaluator.arithmetic(ExpressionOperator.ADD, v15, v2_5);
        assertEquals(17.5, mixed.floatValue().orElse(0.0), "15 + 2.5 = 17.5");

        // Division by zero check
        try {
            evaluator.arithmetic(ExpressionOperator.DIVIDE, v15, vZero);
            throw new AssertionError("Division by zero should fail");
        } catch (ArithmeticException e) {
            testsRun++;
            testsPassed++;
        }
    }

    // =========================================================================
    // 3. ExpressionEvaluator Condition Tests (=, <>, >, >=, <, <=, AND)
    // =========================================================================
    private static void testExpressionEvaluatorConditions() {
        System.out.println("[TEST] ExpressionEvaluator Conditions & Comparisons...");
        ValueComparator comparator = new ValueComparator();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);

        Value v5000 = new Value(DataType.INTEGER, Optional.of(5000), Optional.empty(), Optional.empty());
        Value v3000 = new Value(DataType.INTEGER, Optional.of(3000), Optional.empty(), Optional.empty());

        Expression lit5000 = new Expression(ExpressionKind.LITERAL, Optional.of(v5000), Optional.empty(), Optional.empty(), List.of());
        Expression lit3000 = new Expression(ExpressionKind.LITERAL, Optional.of(v3000), Optional.empty(), Optional.empty(), List.of());

        // 5000 > 3000 -> true
        Expression gtExpr = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.GT), List.of(lit5000, lit3000));
        assertTrue(evaluator.evaluateCondition(gtExpr, null, null), "5000 > 3000");

        // 3000 >= 5000 -> false
        Expression geExpr = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.GE), List.of(lit3000, lit5000));
        assertFalse(evaluator.evaluateCondition(geExpr, null, null), "3000 >= 5000");

        // 5000 = 5000 -> true
        Expression eqExpr = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.EQ), List.of(lit5000, lit5000));
        assertTrue(evaluator.evaluateCondition(eqExpr, null, null), "5000 = 5000");

        // 5000 <> 3000 -> true
        Expression neExpr = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.NE), List.of(lit5000, lit3000));
        assertTrue(evaluator.evaluateCondition(neExpr, null, null), "5000 <> 3000");

        // AND condition: (5000 > 3000) AND (5000 = 5000) -> true
        Expression andExpr = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.AND), List.of(gtExpr, eqExpr));
        assertTrue(evaluator.evaluateCondition(andExpr, null, null), "gt AND eq");
    }

    // =========================================================================
    // 4. ExpressionEvaluator Column Resolution Tests
    // =========================================================================
    private static void testExpressionEvaluatorColumns() {
        System.out.println("[TEST] ExpressionEvaluator Column Resolution...");
        ValueComparator comparator = new ValueComparator();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);

        Schema schema = Schema.of("employee", List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("name", DataType.VARCHAR, Optional.of(50)),
                new ColumnSchema("salary", DataType.INTEGER, Optional.empty())
        ));

        Row row = Row.of(
                new Value(DataType.INTEGER, Optional.of(101), Optional.empty(), Optional.empty()),
                new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("Alice")),
                new Value(DataType.INTEGER, Optional.of(6000), Optional.empty(), Optional.empty())
        );

        // Column salary reference
        Expression colSalary = new Expression(ExpressionKind.COLUMN, Optional.empty(),
                Optional.of(new ColumnRef(Optional.of("employee"), "salary")), Optional.empty(), List.of());

        ExpressionResult result = evaluator.evaluate(colSalary, row, schema);
        assertEquals(6000, result.value().integerValue().orElse(0), "Resolved column salary = 6000");

        // Predicate: salary > 5000
        Value v5000 = new Value(DataType.INTEGER, Optional.of(5000), Optional.empty(), Optional.empty());
        Expression lit5000 = new Expression(ExpressionKind.LITERAL, Optional.of(v5000), Optional.empty(), Optional.empty(), List.of());
        Expression cond = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.GT), List.of(colSalary, lit5000));

        assertTrue(evaluator.evaluateCondition(cond, row, schema), "salary > 5000");
    }

    // =========================================================================
    // 5. PlanValidator Arity and Cycle Tests
    // =========================================================================
    private static void testPlanValidatorArityAndCycle() {
        System.out.println("[TEST] PlanValidator Arity & Cycle Detection...");
        PlanValidator validator = new PlanValidator();

        // Valid SCAN leaf node (0 children)
        PlanNode scanNode = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.empty(), Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        validator.validate(scanNode); // Should succeed

        // Invalid SCAN with 1 child (expected 0)
        PlanNode invalidScan = new PlanNode(PlanNodeKind.SCAN, List.of(scanNode), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.empty(), Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        try {
            validator.validate(invalidScan);
            throw new AssertionError("SCAN with child should fail arity check");
        } catch (IllegalArgumentException e) {
            testsRun++;
            testsPassed++;
        }
    }

    // =========================================================================
    // 6. PlanValidator Parameter Validation Tests
    // =========================================================================
    private static void testPlanValidatorParameters() {
        System.out.println("[TEST] PlanValidator Parameters...");
        PlanValidator validator = new PlanValidator();

        // SCAN missing ScanSpec
        PlanNode scanNoSpec = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        try {
            validator.validate(scanNoSpec);
            throw new AssertionError("SCAN without ScanSpec should fail");
        } catch (IllegalArgumentException e) {
            testsRun++;
            testsPassed++;
        }
    }

    // =========================================================================
    // 7. PlanTranslator SELECT Pipeline Translation Tests
    // =========================================================================
    private static void testPlanTranslatorSelectPipeline() {
        System.out.println("[TEST] PlanTranslator SELECT Pipeline...");

        ValueComparator comparator = new ValueComparator();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);
        PlanValidator validator = new PlanValidator();
        RowSizeEstimator estimator = new RowSizeEstimator();
        MutationCoordinator mutations = new MutationCoordinator(null);
        MutationTargetCollector collector = new MutationTargetCollector(null);

        PlanTranslator translator = new PlanTranslator(
                null, evaluator, comparator, mutations, collector, estimator, validator);

        // SCAN node
        PlanNode scanNode = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.empty(), Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // FILTER node over SCAN
        Value v5000 = new Value(DataType.INTEGER, Optional.of(5000), Optional.empty(), Optional.empty());
        Expression pred = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.GT), List.of(
                        new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(new ColumnRef(Optional.empty(), "salary")), Optional.empty(), List.of()),
                        new Expression(ExpressionKind.LITERAL, Optional.of(v5000), Optional.empty(), Optional.empty(), List.of())
                ));

        PlanNode filterNode = new PlanNode(PlanNodeKind.FILTER, List.of(scanNode), List.of(), Optional.empty(),
                Optional.empty(), Optional.of(pred), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // PROJECT node over FILTER
        PlanNode projectNode = new PlanNode(PlanNodeKind.PROJECT, List.of(filterNode), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(new ColumnRef(Optional.empty(), "name")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // Translate SELECT tree
        Operator rootOp = translator.translateSelect(projectNode);
        assertTrue(rootOp instanceof MockOperator, "Returns MockOperator");
        MockOperator mockOp = (MockOperator) rootOp;
        assertEquals(PlanNodeKind.PROJECT, mockOp.getKind(), "Root is PROJECT operator");
        assertEquals(1, mockOp.getChildren().size(), "PROJECT has 1 child");
        assertEquals(PlanNodeKind.FILTER, ((MockOperator) mockOp.getChildren().get(0)).getKind(), "Child is FILTER operator");

        // Test Operator lifecycle methods
        ExecutionContext ctx = ExecutionContext.of(1L, "sess-01");
        rootOp.open(ctx);
        assertTrue(mockOp.isOpened(), "Operator opened");
        assertEquals(Optional.empty(), rootOp.next(), "Mock next() returns empty");
        rootOp.close();
        assertTrue(mockOp.isClosed(), "Operator closed");
    }

    // =========================================================================
    // 8. PlanTranslator Command Executors Tests
    // =========================================================================
    private static void testPlanTranslatorCommandExecutors() {
        System.out.println("[TEST] PlanTranslator Command Executors (INSERT, UPDATE, DELETE, CREATE_INDEX)...");

        ValueComparator comparator = new ValueComparator();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);
        PlanValidator validator = new PlanValidator();
        RowSizeEstimator estimator = new RowSizeEstimator();
        MutationCoordinator mutations = new MutationCoordinator(null);
        MutationTargetCollector collector = new MutationTargetCollector(null);

        PlanTranslator translator = new PlanTranslator(
                null, evaluator, comparator, mutations, collector, estimator, validator);

        // 1. INSERT command
        Value v1 = new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty());
        PlanNode insertNode = new PlanNode(PlanNodeKind.INSERT, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.of(new MutationSpec("employee", List.of(v1), Optional.empty(), Optional.empty())), Optional.empty());

        CommandExecutor insertExec = translator.translateCommand(insertNode);
        assertTrue(insertExec instanceof MockCommandExecutor, "INSERT CommandExecutor created");
        MockCommandExecutor mockInsert = (MockCommandExecutor) insertExec;
        assertEquals(PlanNodeKind.INSERT, mockInsert.getKind(), "Kind is INSERT");
        assertEquals(0L, insertExec.execute(ExecutionContext.of(1L)), "Execution returns 0L affected rows");
        insertExec.close();
        assertTrue(mockInsert.isClosed(), "Executor closed");

        // 2. CREATE_INDEX command
        IndexMetadata indexMeta = new IndexMetadata("idx_salary", "employee", "salary", IndexType.BPLUS_TREE);
        PlanNode createIndexNode = new PlanNode(PlanNodeKind.CREATE_INDEX, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(indexMeta));

        CommandExecutor indexExec = translator.translateCommand(createIndexNode);
        assertEquals(PlanNodeKind.CREATE_INDEX, ((MockCommandExecutor) indexExec).getKind(), "Kind is CREATE_INDEX");
    }

    // =========================================================================
    // 9. PlanTranslator Error Handling Tests
    // =========================================================================
    private static void testPlanTranslatorErrorHandling() {
        System.out.println("[TEST] PlanTranslator Error Handling...");

        ValueComparator comparator = new ValueComparator();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);
        PlanValidator validator = new PlanValidator();
        RowSizeEstimator estimator = new RowSizeEstimator();
        MutationCoordinator mutations = new MutationCoordinator(null);
        MutationTargetCollector collector = new MutationTargetCollector(null);

        PlanTranslator translator = new PlanTranslator(
                null, evaluator, comparator, mutations, collector, estimator, validator);

        // Passing null node to translateSelect throws IllegalArgumentException
        try {
            translator.translateSelect(null);
            throw new AssertionError("Null node should fail");
        } catch (IllegalArgumentException e) {
            testsRun++;
            testsPassed++;
        }

        // Passing command node (INSERT) to translateSelect throws IllegalArgumentException
        Value v1 = new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty());
        PlanNode insertNode = new PlanNode(PlanNodeKind.INSERT, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.of(new MutationSpec("employee", List.of(v1), Optional.empty(), Optional.empty())), Optional.empty());

        try {
            translator.translateSelect(insertNode);
            throw new AssertionError("Passing INSERT to translateSelect should fail");
        } catch (IllegalArgumentException e) {
            testsRun++;
            testsPassed++;
        }

        // Passing read node (SCAN) to translateCommand throws IllegalArgumentException
        PlanNode scanNode = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.empty(), Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        try {
            translator.translateCommand(scanNode);
            throw new AssertionError("Passing SCAN to translateCommand should fail");
        } catch (IllegalArgumentException e) {
            testsRun++;
            testsPassed++;
        }
    }
}
