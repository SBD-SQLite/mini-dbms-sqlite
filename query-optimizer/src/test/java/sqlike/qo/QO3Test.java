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
        test("scalar subquery estimates equality, range, and empty results", () -> {
            PlanNode scalarResult = PlanNodes.project(PlanNodes.scan("dept"),
                    List.of(Refs.column("dept", "dept_id")));
            Expression scalar = subquery(SubqueryType.SCALAR, scalarResult, false, List.of());

            PlanNode equality = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), binary(ExpressionOperator.EQ,
                            columnExpression(Refs.column("students", "id")), scalar))), Optional.empty()));
            eq(1L, estimator.estimateRows(equality));

            PlanNode range = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), binary(ExpressionOperator.GT,
                            columnExpression(Refs.column("students", "gpa")), scalar))), Optional.empty()));
            eq(2500L, estimator.estimateRows(range));

            PlanNode emptyResult = PlanNodes.project(
                    PlanNodes.limit(PlanNodes.scan("dept"), new sqlike.shared.LimitSpec(0)),
                    List.of(Refs.column("dept", "dept_id")));
            Expression emptyScalar = subquery(SubqueryType.SCALAR, emptyResult, false, List.of());
            PlanNode emptyComparison = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), binary(ExpressionOperator.EQ,
                            columnExpression(Refs.column("students", "id")), emptyScalar))), Optional.empty()));
            eq(0L, estimator.estimateRows(emptyComparison));
        });
        test("subquery costs include execution and correlated repetition", () -> {
            Expression exists = subquery(SubqueryType.EXISTS, PlanNodes.scan("dept"), false, List.of());
            PlanNode uncorrelated = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), exists)), Optional.empty()));
            double baseScanCost = new CostModel(storage).getCost(PlanNodes.scan("students"));
            double uncorrelatedCost = new CostModel(storage).getCost(uncorrelated);
            if (uncorrelatedCost <= baseScanCost)
                throw new AssertionError("expected uncorrelated subquery I/O to be charged");

            PlanNode scalarRoot = PlanNodes.project(PlanNodes.scan("dept"),
                    List.of(Refs.column("dept", "dept_id")));
            Expression scalar = subquery(SubqueryType.SCALAR, scalarRoot, false, List.of());
            PlanNode scalarFilter = binder.bind(new ParsedQuery(StatementType.SELECT,
                    Optional.of(PlanNodes.filter(PlanNodes.scan("students"), binary(ExpressionOperator.EQ,
                            columnExpression(Refs.column("students", "id")), scalar))), Optional.empty()));
            if (new CostModel(storage).getCost(scalarFilter) <= baseScanCost)
                throw new AssertionError("expected scalar subquery I/O to be charged");

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

        subqueryCorrelationTests(optimizer, storage);
        naturalJoinTests(optimizer, storage);

        System.out.printf("QO3: %d passed, %d failed%n", passed, failed);
        return failed;
    }

    /** Subqueries written in SQL: the Binder derives {@code correlated} from the columns the subquery actually reads. */
    private static void subqueryCorrelationTests(QueryOptimizer optimizer, IStorageManager storage) {
        test("SQL EXISTS with an outer alias is correlated and its outer column is qualified by the alias", () -> {
            PlanNode plan = optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id)")).root();
            PlanNodes.validate(plan);
            List<SubquerySpec> found = subqueries(plan);
            eq(1, found.size());
            eq(SubqueryType.EXISTS, found.get(0).type());
            eq(true, found.get(0).correlated());
            eq(true, renderedPlan(found.get(0).root()).contains("(t.id = s.id)"));
        });
        test("SQL correlated IN and scalar subqueries are flagged, uncorrelated ones are not", () -> {
            eq(true, onlySubquery(optimizer, "SELECT name FROM students AS s WHERE s.id IN "
                    + "(SELECT t.id FROM takes AS t WHERE t.course_id = s.dept_id)").correlated());
            eq(true, onlySubquery(optimizer, "SELECT name FROM students AS s WHERE s.gpa > "
                    + "(SELECT t.course_id FROM takes AS t WHERE t.id = s.id)").correlated());
            eq(false, onlySubquery(optimizer, "SELECT name FROM students WHERE id IN (SELECT id FROM takes)").correlated());
            eq(false, onlySubquery(optimizer, "SELECT name FROM students WHERE EXISTS (SELECT * FROM dept WHERE dept_id = 1)").correlated());
            eq(false, onlySubquery(optimizer, "SELECT name FROM students WHERE id = (SELECT id FROM takes WHERE course_id = 1)").correlated());
        });
        test("outer reference by full table name (no alias) is resolved", () -> {
            SubquerySpec spec = onlySubquery(optimizer,
                    "SELECT name FROM students WHERE EXISTS (SELECT * FROM takes WHERE takes.id = students.id)");
            eq(true, spec.correlated());
            eq(true, renderedPlan(spec.root()).contains("(takes.id = students.id)"));
        });
        test("an unqualified inner column wins over the outer column of the same name", () -> {
            // takes.id exists in the subquery, so the unqualified id is not an outer reference.
            eq(false, onlySubquery(optimizer, "SELECT name FROM students WHERE EXISTS (SELECT * FROM takes WHERE id = 1)").correlated());
        });
        test("an inner alias shadows the outer alias of the same name", () -> {
            SubquerySpec spec = onlySubquery(optimizer,
                    "SELECT name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS s WHERE s.id = 1)");
            eq(false, spec.correlated());
            eq(true, renderedPlan(spec.root()).contains("takes AS s"));
        });
        test("nested subqueries: reference to the outermost query flags both levels", () -> {
            List<SubquerySpec> found = subqueries(optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id AND "
                            + "t.course_id IN (SELECT d.dept_id FROM dept AS d WHERE d.dept_id = s.dept_id))")).root());
            eq(2, found.size());
            eq(true, found.get(0).correlated());
            eq(true, found.get(1).correlated());
        });
        test("nested subqueries: inner references only the middle query, middle references the outer one", () -> {
            List<SubquerySpec> found = subqueries(optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id AND "
                            + "EXISTS (SELECT * FROM dept AS d WHERE d.dept_id = t.course_id))")).root());
            eq(2, found.size());
            eq(true, found.get(0).correlated());
            eq(true, found.get(1).correlated());
        });
        test("nested subqueries: an uncorrelated inner subquery stays uncorrelated inside a correlated one", () -> {
            List<SubquerySpec> found = subqueries(optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id AND "
                            + "t.course_id IN (SELECT d.dept_id FROM dept AS d))")).root());
            eq(2, found.size());
            eq(true, found.get(0).correlated());
            eq(false, found.get(1).correlated());
        });
        test("nested subqueries: a middle query that reads no outer column is uncorrelated although its inner one is correlated to it", () -> {
            List<SubquerySpec> found = subqueries(optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT name FROM students WHERE EXISTS (SELECT * FROM takes AS t WHERE EXISTS "
                            + "(SELECT * FROM dept AS d WHERE d.dept_id = t.course_id))")).root());
            eq(2, found.size());
            eq(false, found.get(0).correlated());
            eq(true, found.get(1).correlated());
        });
        test("correlated subquery plans pass PlanNodes.validate", () -> {
            for (String sql : List.of(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id)",
                    "SELECT s.name FROM students AS s WHERE s.id IN (SELECT t.id FROM takes AS t WHERE t.course_id = s.dept_id)",
                    "SELECT name FROM students AS s WHERE s.gpa > (SELECT t.course_id FROM takes AS t WHERE t.id = s.id)",
                    "UPDATE students SET gpa = 4.0 WHERE EXISTS (SELECT * FROM takes WHERE takes.id = students.id)",
                    "DELETE FROM students WHERE id IN (SELECT takes.id FROM takes WHERE takes.course_id = students.dept_id)"))
                PlanNodes.validate(optimizer.optimizeQuery(optimizer.parseQuery(sql)).root());
        });
        test("outer columns do not leak: the outer query cannot see subquery tables, siblings cannot see each other", () -> {
            expectSemantic(() -> optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT name FROM students WHERE EXISTS (SELECT * FROM takes WHERE takes.id = students.id) AND takes.id = 1")));
            expectSemantic(() -> optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT name FROM students WHERE EXISTS (SELECT * FROM takes WHERE takes.id = students.id) "
                            + "AND EXISTS (SELECT * FROM dept WHERE dept.dept_id = takes.id)")));
        });
        test("a second Binder call does not inherit the outer scope of the previous statement", () -> {
            Binder binder = new Binder(storage);
            binder.bind(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id)"));
            expectSemantic(() -> binder.bind(optimizer.parseQuery("SELECT name FROM students WHERE s.id = 1")));
        });
        test("subquery errors: unknown outer column, unknown inner column, wrong width, incompatible IN type", () -> {
            for (String sql : List.of(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = zz.id)",
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.nocol)",
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.nocol = s.id)",
                    "SELECT s.name FROM students AS s WHERE s.id IN (SELECT t.id, t.course_id FROM takes AS t WHERE t.id = s.id)",
                    "SELECT s.name FROM students AS s WHERE s.gpa > (SELECT t.id, t.course_id FROM takes AS t WHERE t.id = s.id)",
                    "SELECT s.name FROM students AS s WHERE s.name IN (SELECT t.id FROM takes AS t WHERE t.id = s.id)"))
                expectSemantic(() -> optimizer.optimizeQuery(optimizer.parseQuery(sql)));
        });
        test("EXISTS accepts any column list, including one column and *", () -> {
            optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT t.id, t.course_id FROM takes AS t WHERE t.id = s.id)"));
            optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT t.id FROM takes AS t WHERE t.id = s.id)"));
        });
        test("cost: an uncorrelated subquery is charged once, a correlated one once per outer row", () -> {
            CostModel costs = new CostModel(storage);
            CardinalityEstimator estimator = new CardinalityEstimator(storage);
            PlanNode uncorrelated = optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.course_id = 1)")).root();
            PlanNode correlated = optimizer.optimizeQuery(optimizer.parseQuery(
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.course_id = s.dept_id)")).root();
            PlanNode filterUncorrelated = uncorrelated.children().get(0), filterCorrelated = correlated.children().get(0);
            double outerScan = costs.getCost(filterUncorrelated.children().get(0));
            double innerUncorrelated = costs.getCost(subqueries(uncorrelated).get(0).root());
            double innerCorrelated = costs.getCost(subqueries(correlated).get(0).root());
            long outerRows = estimator.estimateRows(filterCorrelated.children().get(0));
            close(outerScan + innerUncorrelated, costs.getCost(uncorrelated));
            close(outerScan + outerRows * innerCorrelated, costs.getCost(correlated));
        });
    }

    /** NATURAL JOIN: the common columns appear once and may be referenced without a qualifier. */
    private static void naturalJoinTests(QueryOptimizer optimizer, IStorageManager storage) {
        Binder binder = new Binder(storage);
        test("NATURAL JOIN common column is unambiguous in SELECT, WHERE and ORDER BY", () -> {
            for (String sql : List.of(
                    "SELECT id FROM students NATURAL JOIN takes ORDER BY id",
                    "SELECT id, name FROM students NATURAL JOIN takes WHERE id > 3",
                    "SELECT name FROM students NATURAL JOIN takes ORDER BY id DESC",
                    "SELECT dept_id, dname FROM students NATURAL JOIN dept WHERE dept_id = 2")) {
                PlanNodes.validate(optimizer.optimizeQuery(optimizer.parseQuery(sql)).root());
                binder.bind(optimizer.parseQuery(sql));
            }
        });
        test("NATURAL JOIN unqualified common column binds to the left side; both qualifiers stay usable", () -> {
            PlanNode bound = binder.bind(optimizer.parseQuery("SELECT id FROM students NATURAL JOIN takes ORDER BY id"));
            eq("students.id", renderColumn(bound.projection().get(0)));
            eq("students.id", renderColumn(bound.children().get(0).sort().orElseThrow().column()));
            PlanNode both = binder.bind(optimizer.parseQuery("SELECT students.id, takes.id FROM students NATURAL JOIN takes"));
            eq(List.of("students.id", "takes.id"), both.projection().stream().map(QO3Test::renderColumn).toList());
        });
        test("NATURAL JOIN keeps its output schema: left columns first, common columns once", () -> {
            PlanNode bound = binder.bind(optimizer.parseQuery("SELECT * FROM students NATURAL JOIN dept"));
            eq(List.of("students.id", "students.name", "students.gpa", "students.dept_id", "dept.dname"),
                    bound.projection().stream().map(QO3Test::renderColumn).toList());
            PlanNode join = bound.children().get(0);
            eq(List.of("dept_id"), join.join().orElseThrow().commonColumns());
            eq(List.of("id", "name", "gpa", "dept_id", "dname"), join.outputColumns().stream().map(c -> c.name()).toList());
        });
        test("three-table NATURAL JOIN resolves common columns of each step", () -> {
            PlanNode bound = binder.bind(optimizer.parseQuery(
                    "SELECT dept_id, id, dname FROM students NATURAL JOIN dept NATURAL JOIN takes ORDER BY dept_id"));
            eq(List.of("students.dept_id", "students.id", "dept.dname"),
                    bound.projection().stream().map(QO3Test::renderColumn).toList());
            PlanNode star = binder.bind(optimizer.parseQuery("SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes"));
            eq(List.of("id", "name", "gpa", "dept_id", "dname", "course_id", "grade"),
                    star.projection().stream().map(sqlike.shared.ColumnRef::columnName).toList());
        });
        test("comma plus NATURAL JOIN takes common columns from the left product only", () -> {
            // students x dept repeats dept_id, but the NATURAL JOIN with takes only shares id.
            PlanNode star = binder.bind(optimizer.parseQuery("SELECT * FROM students, dept NATURAL JOIN takes"));
            PlanNode join = star.children().get(0);
            eq(List.of("id"), join.join().orElseThrow().commonColumns());
            eq(List.of("students.id", "students.name", "students.gpa", "students.dept_id", "dept.dept_id", "dept.dname",
                    "takes.course_id", "takes.grade"), star.projection().stream().map(QO3Test::renderColumn).toList());
            PlanNodes.validate(optimizer.optimizeQuery(optimizer.parseQuery("SELECT * FROM students, dept NATURAL JOIN takes")).root());
            // A column that is repeated on the left but not shared stays ambiguous when referenced unqualified.
            expectSemantic(() -> binder.bind(optimizer.parseQuery("SELECT dept_id FROM students, dept NATURAL JOIN takes")));
            binder.bind(optimizer.parseQuery("SELECT students.dept_id, dept.dept_id, id FROM students, dept NATURAL JOIN takes"));
        });
        test("NATURAL JOIN with a common column repeated on the left is a SEMANTIC_ERROR from the Binder", () -> {
            String sql = "SELECT * FROM students, dept NATURAL JOIN dept AS d2";
            try {
                binder.bind(optimizer.parseQuery(sql));
                throw new AssertionError("expected semantic error");
            } catch (QoException error) {
                eq(ErrorCode.SEMANTIC_ERROR, error.getInfo().code());
                eq(true, error.getInfo().message().contains("ambiguous natural join column"));
            }
            expectSemantic(() -> optimizer.optimizeQuery(optimizer.parseQuery(sql)));
        });
        test("NATURAL JOIN without common columns is a SEMANTIC_ERROR; other joins still report ambiguous columns", () -> {
            expectSemantic(() -> binder.bind(optimizer.parseQuery("SELECT * FROM dept NATURAL JOIN takes")));
            expectSemantic(() -> binder.bind(optimizer.parseQuery("SELECT id FROM students, takes")));
            expectSemantic(() -> binder.bind(optimizer.parseQuery("SELECT id FROM students JOIN takes ON students.id = takes.id")));
        });
    }

    private static String renderColumn(sqlike.shared.ColumnRef ref) {
        return ref.tableName().map(table -> table + ".").orElse("") + ref.columnName();
    }

    private static SubquerySpec onlySubquery(QueryOptimizer optimizer, String sql) {
        PlanNode plan = optimizer.optimizeQuery(optimizer.parseQuery(sql)).root();
        PlanNodes.validate(plan);
        List<SubquerySpec> found = subqueries(plan);
        eq(1, found.size());
        return found.get(0);
    }

    /** Every subquery reachable from {@code plan}, outer ones before the ones nested inside them. */
    private static List<SubquerySpec> subqueries(PlanNode plan) {
        List<SubquerySpec> found = new java.util.ArrayList<>();
        collectSubqueries(plan, found);
        return found;
    }

    private static void collectSubqueries(PlanNode node, List<SubquerySpec> found) {
        node.predicate().ifPresent(e -> collectSubqueries(e, found));
        node.join().flatMap(JoinSpec::condition).ifPresent(e -> collectSubqueries(e, found));
        node.mutation().flatMap(m -> m.updateExpression()).ifPresent(e -> collectSubqueries(e, found));
        node.children().forEach(child -> collectSubqueries(child, found));
    }

    private static void collectSubqueries(Expression expression, List<SubquerySpec> found) {
        expression.subquery().ifPresent(spec -> {
            found.add(spec);
            collectSubqueries(spec.root(), found);
        });
        expression.operands().forEach(operand -> collectSubqueries(operand, found));
    }

    private static String renderedPlan(PlanNode plan) {
        return new PlanPrinter().print(plan);
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
