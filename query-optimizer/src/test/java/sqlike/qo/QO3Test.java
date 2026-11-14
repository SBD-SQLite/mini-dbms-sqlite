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
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.ExpressionOperator;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexOperator;
import sqlike.shared.IndexStatistics;
import sqlike.shared.IndexType;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.ScanSpec;
import sqlike.shared.ParsedQuery;
import sqlike.shared.SubquerySpec;
import sqlike.shared.SubqueryType;
import sqlike.shared.StatementType;
import sqlike.shared.SortDirection;
import sqlike.shared.SortSpec;
import sqlike.shared.TableStatistics;
import java.util.OptionalInt;
import java.util.OptionalLong;

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
        test("index scan costs use SM B+Tree and HASH statistics", () -> {
            IndexMetadata tree = new IndexMetadata("ix_students_id_tree", "students", "id", IndexType.BPLUS_TREE);
            IndexStatistics treeStats = new IndexStatistics(OptionalInt.of(3), OptionalInt.empty(),
                    OptionalLong.of(10), OptionalLong.empty(), false);
            IStorageManager treeStorage = MockStorageManager.create(List.of(tree), Map.of("ix_students_id_tree", treeStats));
            PlanNode treeLookup = PlanNodes.indexScan("students", tree.indexName(),
                    new IndexCondition(IndexOperator.EQ, integerValue(1), Optional.empty(), true, true));
            // Descriptor + 3 B+Tree levels + one unclustered heap page.
            close(20.5, new CostModel(treeStorage).getCost(treeLookup));

            IndexMetadata hash = new IndexMetadata("ix_students_id_hash", "students", "id", IndexType.HASH);
            IndexStatistics hashStats = new IndexStatistics(OptionalInt.empty(), OptionalInt.of(10),
                    OptionalLong.empty(), OptionalLong.of(4), false);
            IStorageManager hashStorage = MockStorageManager.create(List.of(hash), Map.of("ix_students_id_hash", hashStats));
            PlanNode hashLookup = PlanNodes.indexScan("students", hash.indexName(),
                    new IndexCondition(IndexOperator.EQ, integerValue(1), Optional.empty(), true, true));
            // Descriptor + directory + bucket + average 0.4 overflow page + one heap page.
            close(18.04, new CostModel(hashStorage).getCost(hashLookup));
        });
        test("CostModel accepts a configurable buffer budget", () -> {
            JoinSpec cartesian = new JoinSpec(JoinType.CARTESIAN, Optional.empty(),
                    JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
            PlanNode plan = PlanNodes.join(PlanNodes.scan("students"), PlanNodes.scan("dept"), cartesian);
            close(207.5, new CostModel(storage, 4).getCost(plan));
            try {
                new CostModel(storage, 2);
                throw new AssertionError("expected invalid buffer budget to be rejected");
            } catch (IllegalArgumentException expected) { }
        });
        test("external-sort cost matches the course oracle for 100 blocks", () -> {
            IStorageManager sortStorage = (IStorageManager) Proxy.newProxyInstance(
                    QO3Test.class.getClassLoader(), new Class<?>[] { IStorageManager.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("getStatistics")
                                && ((String) args[0]).equalsIgnoreCase("students"))
                            return new TableStatistics(5000, 100, 40, 50, Map.of("id", 5000L));
                        try {
                            return method.invoke(storage, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            PlanNode sorted = PlanNodes.sort(PlanNodes.scan("students"),
                    new SortSpec(Refs.column("students", "id"), SortDirection.ASC));
            close(4802.0, new CostModel(sortStorage, 3).getCost(sorted));
        });
        test("ambiguous NATURAL JOIN input column reports semantic error", () -> {
            try {
                binder.bind(optimizer.parseQuery("SELECT * FROM students JOIN dept ON students.dept_id = dept.dept_id NATURAL JOIN dept"));
                throw new AssertionError("expected ambiguous natural join error");
            } catch (QoException error) {
                if (error.getInfo().code() != ErrorCode.SEMANTIC_ERROR
                        || !error.getMessage().toLowerCase().contains("ambiguous natural join column"))
                    throw error;
            }
        });
        test("table aliases resolve columns and hide base qualifiers", () -> {
            PlanNode aliased = scanWithAlias("students", "s");
            PlanNode projection = PlanNodes.project(aliased, List.of(Refs.column("s", "id")));
            PlanNode bound = binder.bind(new ParsedQuery(StatementType.SELECT, Optional.of(projection), Optional.empty()));
            eq(Optional.of("s"), bound.projection().get(0).tableName());
            Expression equality = binary(ExpressionOperator.EQ,
                    columnExpression(Refs.column("s", "id")),
                    new Expression(ExpressionKind.LITERAL, Optional.of(new sqlike.shared.Value(sqlike.shared.DataType.INTEGER,
                                                        Optional.of(1), Optional.empty(), Optional.empty())),
                            Optional.empty(), Optional.empty(), List.of()));
            PlanNode filtered = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(scanWithAlias("students", "s"), equality)), Optional.empty()));
            eq(1L, estimator.estimateRows(filtered));
            expectSemantic(() -> binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.project(scanWithAlias("students", "s"),
                            List.of(Refs.column("students", "id")))), Optional.empty())));
        });
        test("duplicate table aliases are rejected", () -> {
            PlanNode left = scanWithAlias("students", "s");
            PlanNode right = scanWithAlias("dept", "s");
            JoinSpec cartesian = new JoinSpec(JoinType.CARTESIAN, Optional.empty(),
                    JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
            expectSemantic(() -> binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.join(left, right, cartesian)), Optional.empty())));
        });
        test("EXISTS subquery estimates uncorrelated and empty results", () -> {
            Expression exists = subquery(SubqueryType.EXISTS, PlanNodes.scan("dept"), false, List.of());
            PlanNode filter = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), exists)), Optional.empty()));
            eq(5000L, estimator.estimateRows(filter));

            Expression emptyExists = subquery(SubqueryType.EXISTS,
                    PlanNodes.limit(PlanNodes.scan("dept"), new sqlike.shared.LimitSpec(0)), false, List.of());
            PlanNode emptyFilter = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), emptyExists)), Optional.empty()));
            eq(0L, estimator.estimateRows(emptyFilter));
        });
        test("IN subquery estimates filtered result cardinality", () -> {
            PlanNode result = PlanNodes.filter(PlanNodes.scan("dept"), binary(ExpressionOperator.EQ,
                    columnExpression(Refs.column("dept", "dept_id")), intLiteral(10)));
            result = PlanNodes.project(result, List.of(Refs.column("dept", "dept_id")));
            Expression in = subquery(SubqueryType.IN, result, false,
                    List.of(columnExpression(Refs.column("students", "dept_id"))));
            PlanNode filter = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), in)), Optional.empty()));
            long rows = estimator.estimateRows(filter);
            if (rows <= 0 || rows >= 5000) throw new AssertionError("expected reduced IN cardinality, got " + rows);
        });
        test("subquery costs include execution and correlated repetition", () -> {
            Expression exists = subquery(SubqueryType.EXISTS, PlanNodes.scan("dept"), false, List.of());
            PlanNode uncorrelated = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), exists)), Optional.empty()));
            double baseScanCost = new CostModel(storage).getCost(PlanNodes.scan("students"));
            double uncorrelatedCost = new CostModel(storage).getCost(uncorrelated);
            if (uncorrelatedCost <= baseScanCost)
                throw new AssertionError("expected uncorrelated subquery I/O to be charged");

            Expression correlatedPredicate = binary(ExpressionOperator.EQ,
                    columnExpression(Refs.column("dept", "dept_id")),
                    columnExpression(Refs.column("students", "dept_id")));
            PlanNode correlatedRoot = PlanNodes.filter(PlanNodes.scan("dept"), correlatedPredicate);
            Expression correlatedExists = subquery(SubqueryType.EXISTS, correlatedRoot, true, List.of());
            PlanNode correlated = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), correlatedExists)), Optional.empty()));
            double correlatedCost = new CostModel(storage).getCost(correlated);
            if (correlatedCost <= uncorrelatedCost)
                throw new AssertionError("expected correlated subquery cost to scale with outer rows");
        });
        test("IN subquery binds one output column and checks types", () -> {
            PlanNode oneColumn = PlanNodes.project(PlanNodes.scan("dept"), List.of(Refs.column("dept", "dept_id")));
            Expression in = subquery(SubqueryType.IN, oneColumn, false,
                    List.of(columnExpression(Refs.column("students", "dept_id"))));
            binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), in)), Optional.empty()));
            Expression incompatible = subquery(SubqueryType.IN,
                    PlanNodes.project(PlanNodes.scan("dept"), List.of(Refs.column("dept", "name"))), false,
                    List.of(columnExpression(Refs.column("students", "dept_id"))));
            expectSemantic(() -> binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), incompatible)), Optional.empty())));
            Expression tooWide = subquery(SubqueryType.IN, PlanNodes.scan("dept"), false,
                    List.of(columnExpression(Refs.column("students", "dept_id"))));
            expectSemantic(() -> binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), tooWide)), Optional.empty())));
        });
        test("scalar subquery requires one output column and binds its type", () -> {
            PlanNode oneColumn = PlanNodes.project(PlanNodes.scan("dept"), List.of(Refs.column("dept", "dept_id")));
            Expression scalar = subquery(SubqueryType.SCALAR, oneColumn, false, List.of());
            Expression compare = binary(ExpressionOperator.EQ,
                    columnExpression(Refs.column("students", "dept_id")), scalar);
            binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), compare)), Optional.empty()));

            Expression tooWide = subquery(SubqueryType.SCALAR, PlanNodes.scan("dept"), false, List.of());
            Expression invalidCompare = binary(ExpressionOperator.EQ,
                    columnExpression(Refs.column("students", "dept_id")), tooWide);
            expectSemantic(() -> binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), invalidCompare)), Optional.empty())));
        });
        test("correlated subquery resolves outer references", () -> {
            Expression correlatedPredicate = binary(ExpressionOperator.EQ,
                    columnExpression(Refs.column("dept", "dept_id")),
                    columnExpression(Refs.column("students", "dept_id")));
            PlanNode inner = PlanNodes.filter(PlanNodes.scan("dept"), correlatedPredicate);
            Expression exists = subquery(SubqueryType.EXISTS, inner, true, List.of());
            binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), exists)), Optional.empty()));
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

    private static PlanNode scanWithAlias(String table, String alias) {
        return new PlanNode(PlanNodeKind.SCAN, List.of(), List.of(), Optional.empty(),
                Optional.of(new ScanSpec(table, Optional.empty(), Optional.empty(), Optional.of(alias))),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Expression columnExpression(sqlike.shared.ColumnRef column) {
        return new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(column), Optional.empty(), List.of());
    }

    private static sqlike.shared.Value integerValue(int value) {
        return new sqlike.shared.Value(sqlike.shared.DataType.INTEGER,
                Optional.of(value), Optional.empty(), Optional.empty());
    }

    private static Expression intLiteral(int value) {
        return new Expression(ExpressionKind.LITERAL,
                Optional.of(new sqlike.shared.Value(sqlike.shared.DataType.INTEGER,
                        Optional.of(value), Optional.empty(), Optional.empty())),
                Optional.empty(), Optional.empty(), List.of());
    }

    private static Expression binary(ExpressionOperator operator, Expression left, Expression right) {
        return new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(), Optional.of(operator),
                List.of(left, right));
    }

    private static Expression subquery(SubqueryType type, PlanNode root, boolean correlated, List<Expression> operands) {
        return new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(), operands,
                Optional.of(new SubquerySpec(type, root, correlated)));
    }

    private static void expectSemantic(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected semantic error");
        } catch (QoException error) {
            if (error.getInfo().code() != ErrorCode.SEMANTIC_ERROR) throw error;
        }
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
