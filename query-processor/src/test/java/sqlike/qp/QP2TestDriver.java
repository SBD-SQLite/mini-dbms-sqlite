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
 * Standalone test suite and verification driver for QP2 Milestone 2 & PRD v1.0 implementations.
 * Tests PlanTranslator, PlanValidator, ExpressionEvaluator, and ValueComparator.
 */
public class QP2TestDriver {

    private static int testsRun = 0;
    private static int testsPassed = 0;

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   SQLike QP2 (Plan Translator & Evaluator) Tests");
        System.out.println("   Milestone 2 & PRD v1.0 Verification           ");
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
            testPlanTranslatorRealOperatorsAndExecutors();
            testPlanTranslatorBonusOperatorsAndSubqueries();
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

        // CHAR vs VARCHAR and Unicode code-point comparison (supplementary emoji U+1F600 > BMP U+FFFD)
        Value charVal = new Value(DataType.CHAR, Optional.empty(), Optional.empty(), Optional.of("Apple"));
        assertTrue(comparator.equal(textA, charVal), "VARCHAR 'Apple' == CHAR 'Apple'");
        Value bmpHigh = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("\uFFFD"));
        Value suppEmoji = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("\uD83D\uDE00"));
        assertTrue(comparator.compare(bmpHigh, suppEmoji) < 0, "Unicode code-point U+FFFD < U+1F600");

        // NaN float comparison rejected with INVALID_VALUE
        Value nanVal = new Value(DataType.FLOAT, Optional.empty(), Optional.of(Double.NaN), Optional.empty());
        try {
            comparator.compare(float10, nanVal);
            throw new AssertionError("Comparing with NaN should fail");
        } catch (QpException e) {
            assertEquals(sqlike.shared.ErrorCode.INVALID_VALUE, e.getErrorInfo().code(), "NaN comparison throws INVALID_VALUE");
        }

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
        System.out.println("[TEST] ExpressionEvaluator Arithmetic (+, -, *, /, unary -)...");
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

        // Unary negation (-15)
        Expression negExpr = new Expression(ExpressionKind.UNARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.NEGATE),
                List.of(new Expression(ExpressionKind.LITERAL, Optional.of(v15), Optional.empty(), Optional.empty(), List.of())));
        assertEquals(-15, evaluator.evaluate(negExpr, null, null).value().integerValue().orElse(0), "-15 = -15");

        // Division by zero check (direct arithmetic call)
        try {
            evaluator.arithmetic(ExpressionOperator.DIVIDE, v15, vZero);
            throw new AssertionError("Division by zero should fail");
        } catch (ArithmeticException e) {
            testsRun++;
            testsPassed++;
        }

        // Division by zero and integer overflow via evaluate() map to INVALID_VALUE (PRD §8)
        Expression divZeroExpr = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.DIVIDE),
                List.of(
                        new Expression(ExpressionKind.LITERAL, Optional.of(v15), Optional.empty(), Optional.empty(), List.of()),
                        new Expression(ExpressionKind.LITERAL, Optional.of(vZero), Optional.empty(), Optional.empty(), List.of())
                ));
        try {
            evaluator.evaluate(divZeroExpr, null, null);
            throw new AssertionError("Division by zero in evaluate() should throw QpException(INVALID_VALUE)");
        } catch (QpException e) {
            assertEquals(sqlike.shared.ErrorCode.INVALID_VALUE, e.getErrorInfo().code(), "Division by zero maps to INVALID_VALUE");
        }

        Value vMax = new Value(DataType.INTEGER, Optional.of(Integer.MAX_VALUE), Optional.empty(), Optional.empty());
        Expression overflowExpr = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.ADD),
                List.of(
                        new Expression(ExpressionKind.LITERAL, Optional.of(vMax), Optional.empty(), Optional.empty(), List.of()),
                        new Expression(ExpressionKind.LITERAL, Optional.of(v5), Optional.empty(), Optional.empty(), List.of())
                ));
        try {
            evaluator.evaluate(overflowExpr, null, null);
            throw new AssertionError("Integer overflow in evaluate() should throw QpException(INVALID_VALUE)");
        } catch (QpException e) {
            assertEquals(sqlike.shared.ErrorCode.INVALID_VALUE, e.getErrorInfo().code(), "Integer overflow maps to INVALID_VALUE");
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

        // Invalid JOIN with 1 child (expected 2)
        PlanNode invalidJoinArity = new PlanNode(PlanNodeKind.JOIN, List.of(scanNode), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(),
                Optional.of(new JoinSpec(JoinType.NATURAL, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of("id"))),
                Optional.empty(), Optional.empty(), Optional.empty());
        try {
            validator.validate(invalidJoinArity);
            throw new AssertionError("JOIN with 1 child should fail arity check");
        } catch (IllegalArgumentException e) {
            testsRun++;
            testsPassed++;
        }

        // Cycle detection in validateNode when an ancestor node is revisited on the active path
        try {
            java.util.Set<PlanNode> activePath = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            activePath.add(scanNode);
            java.lang.reflect.Method validateNodeMethod =
                    PlanValidator.class.getDeclaredMethod("validateNode", PlanNode.class, java.util.Set.class);
            validateNodeMethod.setAccessible(true);
            validateNodeMethod.invoke(validator, scanNode, activePath);
            throw new AssertionError("Cyclic PlanNode reference should fail cycle detection");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof IllegalArgumentException && cause.getMessage().contains("Cyclic"),
                    "Cycle error message mentions Cyclic");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Reflection failed: " + e.getMessage());
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

        // THETA JOIN missing condition should fail
        PlanNode validScan = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.empty(), Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        PlanNode thetaNoCond = new PlanNode(PlanNodeKind.JOIN, List.of(validScan, validScan), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(),
                Optional.of(new JoinSpec(JoinType.THETA, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of())),
                Optional.empty(), Optional.empty(), Optional.empty());
        try {
            validator.validate(thetaNoCond);
            throw new AssertionError("THETA JOIN without condition should fail");
        } catch (IllegalArgumentException e) {
            testsRun++;
            testsPassed++;
        }

        // FILTER with malformed BINARY expression (only 1 operand) should fail
        Value v1 = new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty());
        Expression badBinary = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.EQ),
                List.of(new Expression(ExpressionKind.LITERAL, Optional.of(v1), Optional.empty(), Optional.empty(), List.of())));
        PlanNode filterBadExpr = new PlanNode(PlanNodeKind.FILTER, List.of(validScan), List.of(), Optional.empty(),
                Optional.empty(), Optional.of(badBinary), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        try {
            validator.validate(filterBadExpr);
            throw new AssertionError("FILTER with 1-operand BINARY expression should fail");
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
    // 8b. PlanTranslator Real Operators & Executors with Stub Storage
    // =========================================================================
    private static void testPlanTranslatorRealOperatorsAndExecutors() throws Exception {
        System.out.println("[TEST] PlanTranslator Real Operators & Executors...");
        java.nio.file.Path snap = java.nio.file.Files.createTempDirectory("qp2-real-").resolve("sm.stub");
        java.util.List<String> trace = new java.util.ArrayList<>();
        sqlike.qp.stub.StubStorage stubSm = new sqlike.qp.stub.StubStorage(snap, trace);
        stubSm.define(new sqlike.shared.TableSchema("1", "employee", List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("name", DataType.VARCHAR, Optional.of(50)),
                new ColumnSchema("salary", DataType.INTEGER, Optional.empty())
        )));

        ValueComparator comparator = new ValueComparator();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);
        PlanValidator validator = new PlanValidator();
        RowSizeEstimator estimator = new RowSizeEstimator();
        MutationCoordinator mutations = new MutationCoordinator(stubSm);
        MutationTargetCollector collector = new MutationTargetCollector(stubSm);

        PlanTranslator translator = new PlanTranslator(
                stubSm, evaluator, comparator, mutations, collector, estimator, validator);

        PlanNode scanNode = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.empty(), Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(translator.translateSelect(scanNode) instanceof TableScanOperator, "Real TableScanOperator created");

        // INDEX_SCAN operator translation
        Value vKey1 = new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty());
        PlanNode indexScanNode = new PlanNode(PlanNodeKind.INDEX_SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.of("idx_emp_id"),
                        Optional.of(new sqlike.shared.IndexCondition(sqlike.shared.IndexOperator.EQ, vKey1, Optional.empty(), true, true)))),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(translator.translateSelect(indexScanNode) instanceof IndexScanOperator, "Real IndexScanOperator created");

        // FILTER and PROJECT operator translation
        Value v5000 = new Value(DataType.INTEGER, Optional.of(5000), Optional.empty(), Optional.empty());
        Expression filterPred = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.GT), List.of(
                        new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(ColumnRef.of("employee", "salary")), Optional.empty(), List.of()),
                        new Expression(ExpressionKind.LITERAL, Optional.of(v5000), Optional.empty(), Optional.empty(), List.of())
                ));
        PlanNode filterNode = new PlanNode(PlanNodeKind.FILTER, List.of(scanNode), List.of(), Optional.empty(),
                Optional.empty(), Optional.of(filterPred), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(translator.translateSelect(filterNode) instanceof FilterOperator, "Real FilterOperator created");

        PlanNode projectNode = new PlanNode(PlanNodeKind.PROJECT, List.of(filterNode), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(ColumnRef.of("employee", "name"), ColumnRef.of("employee", "salary")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(translator.translateSelect(projectNode) instanceof ProjectOperator, "Real ProjectOperator created");

        // NATURAL JOIN deduplicating common columns & prefixed column resolution
        stubSm.define(new sqlike.shared.TableSchema("2", "department", List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("dept_name", DataType.VARCHAR, Optional.of(50))
        )));
        PlanNode deptScan = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("department", Optional.empty(), Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        PlanNode naturalJoinNode = new PlanNode(PlanNodeKind.JOIN, List.of(scanNode, deptScan), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(),
                Optional.of(new JoinSpec(JoinType.NATURAL, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of())),
                Optional.empty(), Optional.empty(), Optional.empty());
        Operator naturalJoinOp = translator.translateSelect(naturalJoinNode);
        assertTrue(naturalJoinOp instanceof BlockNestedLoopJoinOperator, "Real BlockNestedLoopJoinOperator created");
        assertEquals(4, naturalJoinOp.schema().columnCount(), "NATURAL JOIN deduplicates common column 'id' (3 + 2 - 1 = 4)");
        assertEquals(0, naturalJoinOp.schema().findColumnOffset("employee", "id"), "employee.id resolves to offset 0");
        assertEquals(0, naturalJoinOp.schema().findColumnOffset("department", "id"), "department.id also resolves to shared offset 0");
        assertEquals(3, naturalJoinOp.schema().findColumnOffset("department", "dept_name"), "department.dept_name resolves to offset 3");

        PlanNode sortNode = new PlanNode(PlanNodeKind.SORT, List.of(scanNode), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                Optional.of(new SortSpec(ColumnRef.of("employee", "salary"), SortDirection.DESC)),
                Optional.empty(), Optional.empty());
        assertTrue(translator.translateSelect(sortNode) instanceof SortOperator, "Real SortOperator created");

        Value v1 = new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty());
        PlanNode insertNode = new PlanNode(PlanNodeKind.INSERT, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.of(new MutationSpec("employee", List.of(v1), Optional.empty(), Optional.empty())), Optional.empty());
        assertTrue(translator.translateCommand(insertNode) instanceof InsertExecutor, "Real InsertExecutor created");

        // UPDATE and CREATE_INDEX real executors
        Expression addSalary = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.ADD), List.of(
                        new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(ColumnRef.of("employee", "salary")), Optional.empty(), List.of()),
                        new Expression(ExpressionKind.LITERAL, Optional.of(vKey1), Optional.empty(), Optional.empty(), List.of())
                ));
        PlanNode updateNode = new PlanNode(PlanNodeKind.UPDATE, List.of(scanNode), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.of(new MutationSpec("employee", List.of(), Optional.of(ColumnRef.of("employee", "salary")), Optional.of(addSalary))),
                Optional.empty());
        assertTrue(translator.translateCommand(updateNode) instanceof UpdateExecutor, "Real UpdateExecutor created");

        IndexMetadata realIdxMeta = new IndexMetadata("idx_emp_id", "employee", "id", IndexType.BPLUS_TREE);
        PlanNode createIdxNode = new PlanNode(PlanNodeKind.CREATE_INDEX, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(realIdxMeta));
        assertTrue(translator.translateCommand(createIdxNode) instanceof CreateIndexExecutor, "Real CreateIndexExecutor created");

        PlanNode deleteNode = new PlanNode(PlanNodeKind.DELETE, List.of(scanNode), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.of(new MutationSpec("employee", List.of(), Optional.empty(), Optional.empty())), Optional.empty());
        assertTrue(translator.translateCommand(deleteNode) instanceof DeleteExecutor, "Real DeleteExecutor created");
    }

    // =========================================================================
    // 8c. PlanTranslator Bonus Operators, DDL Executors & Subquery Evaluator
    // =========================================================================
    private static void testPlanTranslatorBonusOperatorsAndSubqueries() throws Exception {
        System.out.println("[TEST] PlanTranslator Bonus (LIMIT, CREATE_TABLE, DROP_TABLE, AS, SUBQUERY)...");
        java.nio.file.Path snap = java.nio.file.Files.createTempDirectory("qp2-bonus-").resolve("sm.stub");
        java.util.List<String> trace = new java.util.ArrayList<>();
        sqlike.qp.stub.StubStorage stubSm = new sqlike.qp.stub.StubStorage(snap, trace);
        stubSm.define(new sqlike.shared.TableSchema("1", "employee", List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("name", DataType.VARCHAR, Optional.of(50)),
                new ColumnSchema("salary", DataType.INTEGER, Optional.empty())
        )));

        ValueComparator comparator = new ValueComparator();
        ExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);
        PlanValidator validator = new PlanValidator();
        RowSizeEstimator estimator = new RowSizeEstimator();
        MutationCoordinator mutations = new MutationCoordinator(stubSm);
        MutationTargetCollector collector = new MutationTargetCollector(stubSm);

        PlanTranslator translator = new PlanTranslator(
                stubSm, evaluator, comparator, mutations, collector, estimator, validator);

        // 1. Aliased SCAN + LIMIT operator
        PlanNode aliasedScan = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.empty(), Optional.empty(), Optional.of("e"))),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        PlanNode limitNode = new PlanNode(PlanNodeKind.LIMIT, List.of(aliasedScan), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new sqlike.shared.LimitSpec(5)), Optional.empty(), Optional.empty());
        Operator limitOp = translator.translateSelect(limitNode);
        assertTrue(limitOp instanceof LimitOperator, "Real LimitOperator created");
        assertEquals(0, limitOp.schema().findColumnOffset("e", "id"), "Alias 'e' resolves column offset on Schema");

        // 2. CREATE_TABLE and DROP_TABLE executors
        sqlike.shared.CreateTableSpec createSpec = new sqlike.shared.CreateTableSpec(
                "dept",
                List.of(new ColumnSchema("id", DataType.INTEGER, Optional.empty())),
                List.of("id"),
                List.of());
        PlanNode createTableNode = new PlanNode(PlanNodeKind.CREATE_TABLE, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(createSpec), Optional.empty());
        assertTrue(translator.translateCommand(createTableNode) instanceof CreateTableExecutor, "Real CreateTableExecutor created");

        sqlike.shared.DropTableSpec dropSpec = new sqlike.shared.DropTableSpec("dept", sqlike.shared.DropBehavior.RESTRICT);
        PlanNode dropTableNode = new PlanNode(PlanNodeKind.DROP_TABLE, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(dropSpec));
        assertTrue(translator.translateCommand(dropTableNode) instanceof DropTableExecutor, "Real DropTableExecutor created");

        // 3. WHERE Subqueries (SCALAR 1-row, SCALAR 0-row SQL NULL, IN, EXISTS)
        stubSm.useTransaction(new sqlike.qp.port.QpContracts.TxId(1L));
        stubSm.insert(stubSm.reserveInsert("employee", List.of(
                new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty()),
                new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("Alice")),
                new Value(DataType.INTEGER, Optional.of(7000), Optional.empty(), Optional.empty())
        )), 1L);
        stubSm.insert(stubSm.reserveInsert("employee", List.of(
                new Value(DataType.INTEGER, Optional.of(2), Optional.empty(), Optional.empty()),
                new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("Bob")),
                new Value(DataType.INTEGER, Optional.of(4000), Optional.empty(), Optional.empty())
        )), 2L);

        PlanNode baseScan = new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec("employee", Optional.empty(), Optional.empty())),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        Value vId1 = new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty());
        Expression idEq1 = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.EQ), List.of(
                        new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(ColumnRef.of("employee", "id")), Optional.empty(), List.of()),
                        new Expression(ExpressionKind.LITERAL, Optional.of(vId1), Optional.empty(), Optional.empty(), List.of())
                ));
        PlanNode filterId1 = new PlanNode(PlanNodeKind.FILTER, List.of(baseScan), List.of(), Optional.empty(),
                Optional.empty(), Optional.of(idEq1), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        PlanNode projSalary = new PlanNode(PlanNodeKind.PROJECT, List.of(filterId1), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(ColumnRef.of("employee", "salary")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        Expression scalarSubExpr = new Expression(
                ExpressionKind.SUBQUERY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(new sqlike.shared.SubquerySpec(sqlike.shared.SubqueryType.SCALAR, projSalary, false)));
        ExpressionResult scalarRes = evaluator.evaluate(scalarSubExpr, null, null);
        assertFalse(scalarRes.isSqlNull(), "1-row SCALAR subquery is not SQL NULL");
        assertEquals(7000, scalarRes.value().integerValue().orElse(0), "1-row SCALAR subquery evaluates to 7000");

        // 0-row SCALAR subquery returns SQL NULL and comparison yields false in WHERE
        Value vId99 = new Value(DataType.INTEGER, Optional.of(99), Optional.empty(), Optional.empty());
        Expression idEq99 = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.EQ), List.of(
                        new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(ColumnRef.of("employee", "id")), Optional.empty(), List.of()),
                        new Expression(ExpressionKind.LITERAL, Optional.of(vId99), Optional.empty(), Optional.empty(), List.of())
                ));
        PlanNode filterId99 = new PlanNode(PlanNodeKind.FILTER, List.of(baseScan), List.of(), Optional.empty(),
                Optional.empty(), Optional.of(idEq99), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        PlanNode projEmpty = new PlanNode(PlanNodeKind.PROJECT, List.of(filterId99), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(ColumnRef.of("employee", "salary")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        Expression emptyScalarExpr = new Expression(
                ExpressionKind.SUBQUERY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(new sqlike.shared.SubquerySpec(sqlike.shared.SubqueryType.SCALAR, projEmpty, false)));
        assertTrue(evaluator.evaluate(emptyScalarExpr, null, null).isSqlNull(), "0-row SCALAR subquery returns SQL NULL");
        Expression cmpWithNull = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(ExpressionOperator.EQ), List.of(
                        new Expression(ExpressionKind.LITERAL, Optional.of(vId1), Optional.empty(), Optional.empty(), List.of()),
                        emptyScalarExpr
                ));
        assertFalse(evaluator.evaluateCondition(cmpWithNull, null, null), "Comparison with 0-row SCALAR SQL NULL evaluates to false");

        // >1 row SCALAR subquery throws INVALID_VALUE
        PlanNode projAllSalaries = new PlanNode(PlanNodeKind.PROJECT, List.of(baseScan), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(ColumnRef.of("employee", "salary")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        Expression multiRowScalarExpr = new Expression(
                ExpressionKind.SUBQUERY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(new sqlike.shared.SubquerySpec(sqlike.shared.SubqueryType.SCALAR, projAllSalaries, false)));
        try {
            evaluator.evaluate(multiRowScalarExpr, null, null);
            throw new AssertionError("Multi-row SCALAR subquery should throw INVALID_VALUE");
        } catch (QpException e) {
            assertEquals(sqlike.shared.ErrorCode.INVALID_VALUE, e.getErrorInfo().code(), "Multi-row SCALAR subquery throws INVALID_VALUE");
        }

        // IN and EXISTS subqueries
        Expression inExpr = new Expression(
                ExpressionKind.SUBQUERY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(new Expression(ExpressionKind.LITERAL,
                        Optional.of(new Value(DataType.INTEGER, Optional.of(4000), Optional.empty(), Optional.empty())),
                        Optional.empty(), Optional.empty(), List.of())),
                Optional.of(new sqlike.shared.SubquerySpec(sqlike.shared.SubqueryType.IN, projAllSalaries, false)));
        assertTrue(evaluator.evaluateCondition(inExpr, null, null), "4000 IN (7000, 4000) is true");

        Expression existsExpr = new Expression(
                ExpressionKind.SUBQUERY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(new sqlike.shared.SubquerySpec(sqlike.shared.SubqueryType.EXISTS, filterId1, false)));
        assertTrue(evaluator.evaluateCondition(existsExpr, null, null), "EXISTS non-empty subquery is true");
        Expression notExistsExpr = new Expression(
                ExpressionKind.SUBQUERY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                Optional.of(new sqlike.shared.SubquerySpec(sqlike.shared.SubqueryType.EXISTS, filterId99, false)));
        assertFalse(evaluator.evaluateCondition(notExistsExpr, null, null), "EXISTS empty subquery is false");
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
