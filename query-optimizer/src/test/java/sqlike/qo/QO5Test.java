package sqlike.qo;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

import static sqlike.qo.QO5TestSupport.*;

/** All QO5 acceptance, regression and benchmark checks; grouped in one suite for QODriver. */
public final class QO5Test {
    private static int passed, failed;
    private QO5Test() { }

    public static int run() {
        passed = failed = 0;
        test("Cartesian selection becomes theta join", QO5Test::cartesianToJoin);
        test("theta inequality and arithmetic predicates are preserved", QO5Test::thetaPredicates);
        test("unconnected Cartesian and residual filters remain", QO5Test::residualFilters);
        test("existing join condition is conjoined with selection", QO5Test::mergeJoin);
        test("QO4 projection between selection and Cartesian is handled", QO5Test::projectionBoundary);
        test("selection over NATURAL JOIN retains equality and deduplication", QO5Test::mergeNatural);
        test("two-table candidates include swapped inputs", QO5Test::twoTableOrders);
        test("three-table candidates change join grouping", QO5Test::threeTableOrders);
        test("four-table candidates include a bushy tree", QO5Test::bushyOrders);
        test("NATURAL JOIN permutations retain original representatives", QO5Test::naturalOrders);
        test("mixed natural and theta joins preserve predicates", QO5Test::mixedOrders);
        test("duplicate tuples and repeated SELECT columns survive", QO5Test::duplicateRows);
        test("empty join inputs remain empty with headers", QO5Test::emptyInputs);
        test("ORDER BY on a nonoutput column remains valid", QO5Test::sortOrder);
        test("QO3 chooses a cheaper outer input", QO5Test::costSelection);
        test("selector keeps first candidate on cost ties", QO5Test::stableTies);
        test("candidate budget and enumeration order are deterministic", QO5Test::budget);
        test("LIMIT retains its position while its child is optimized", QO5Test::limitBoundary);
        test("input tree and output metadata are not mutated", QO5Test::immutability);
        test("invalid inputs report domain errors", QO5Test::invalidInputs);
        test("unsupported subquery predicates stay at their original scope", QO5Test::subqueryBoundary);
        test("facade connects QO4 to cost-based QO5", QO5Test::facadeIntegration);
        test("DML and index commands remain outside package-one rewriting", QO5Test::commandPassThrough);
        test("constant predicates are applied exactly once", QO5Test::constantPredicates);
        test("natural join without common columns keeps Cartesian semantics", QO5Test::naturalWithoutCommon);
        test("single-table input keeps its plan instance", QO5Test::singleTable);
        test("four natural joins remain equivalent in bushy candidates", QO5Test::fourNatural);
        test("disconnected join inputs keep every Cartesian combination", QO5Test::disconnectedInputs);
        test("candidate equivalence holds for varied fixture contents", QO5Test::variedContents);
        QO5AccessPathTest.run(QO5Test::test);
        QO5GeneticTest.run(QO5Test::test);
        QO5ScopeTest.run(QO5Test::test);
        QO5DynamicProgrammingTest.run(QO5Test::test);
        QO5JoinSearchBenchmarkTest.run(QO5Test::test);
        System.out.printf("QO5: %d passed, %d failed%n", passed, failed);
        return failed;
    }

    private static final String TWO = "SELECT students.name, dept.dname FROM students JOIN dept ON students.dept_id = dept.dept_id";
    private static final String THREE = "SELECT students.name, dept.dname, takes.grade FROM students "
            + "JOIN dept ON students.dept_id = dept.dept_id JOIN takes ON students.id = takes.id";
    private static final String FOUR = "SELECT students.name, dept.dname, courses.title FROM students "
            + "JOIN dept ON students.dept_id = dept.dept_id JOIN takes ON students.id = takes.id "
            + "JOIN courses ON takes.course_id = courses.course_id";

    private static void cartesianToJoin() {
        PlanNode before = bound("SELECT * FROM students, dept WHERE students.dept_id = dept.dept_id");
        PlanNode after = new JoinRewriter().cartesianToJoin(before);
        check(count(after, PlanNodeKind.CARTESIAN) == 0 && count(after, PlanNodeKind.JOIN) == 1, "missing theta join");
        equivalent(before, after, data());
    }

    private static void thetaPredicates() {
        for (String condition : List.of("students.dept_id < dept.dept_id", "students.dept_id + 1 > dept.dept_id")) {
            PlanNode before = bound("SELECT students.name FROM students, dept WHERE " + condition);
            PlanNode after = new JoinRewriter().cartesianToJoin(before);
            check(count(after, PlanNodeKind.JOIN) == 1, "theta predicate not moved");
            equivalent(before, after, data());
        }
    }

    private static void residualFilters() {
        PlanNode before = bound("SELECT * FROM students, dept WHERE students.dept_id = dept.dept_id AND students.id > 1");
        PlanNode after = new JoinRewriter().cartesianToJoin(before);
        check(count(after, PlanNodeKind.FILTER) == 1, "local residual was dropped");
        equivalent(before, after, data());
        PlanNode unconnected = bound("SELECT * FROM students, dept WHERE students.id > 1");
        PlanNode unchanged = new JoinRewriter().cartesianToJoin(unconnected);
        equal(1, count(unchanged, PlanNodeKind.CARTESIAN));
        equivalent(unconnected, unchanged, data());
    }

    private static void mergeJoin() {
        PlanNode before = bound(TWO + " WHERE students.id < dept.dept_id AND students.gpa > 3");
        PlanNode after = new JoinRewriter().mergeSelectionIntoJoin(before);
        equal(0, count(after, PlanNodeKind.FILTER));
        PlanNode join = firstJoin(after);
        equal(3, QO5Plans.conjuncts(join.join().orElseThrow().condition().orElseThrow()).size());
        equivalent(before, after, data());
    }

    private static void projectionBoundary() {
        PlanNode before = rewritten("SELECT students.name, dept.dname FROM students, dept "
                + "WHERE students.dept_id = dept.dept_id AND students.gpa > 3");
        PlanNode after = new JoinRewriter().cartesianToJoin(before);
        equal(0, count(after, PlanNodeKind.CARTESIAN));
        equivalent(before, after, data());
    }

    private static void mergeNatural() {
        PlanNode before = bound("SELECT * FROM students NATURAL JOIN dept WHERE students.gpa > 3");
        PlanNode after = new JoinRewriter().mergeSelectionIntoJoin(before);
        equivalent(before, after, data());
        equal(before.outputColumns(), after.outputColumns());
        check(count(after, PlanNodeKind.FILTER) == 0, "selection not merged");
    }

    private static void twoTableOrders() {
        PlanNode before = rewritten(TWO);
        List<PlanNode> candidates = candidates(before);
        check(candidates.size() > 1, "only one order");
        check(candidates.stream().anyMatch(candidate -> firstJoin(candidate).children().get(0).outputColumns().stream()
                .anyMatch(column -> column.source().orElseThrow().tableName().orElseThrow().equals("dept"))), "inputs never swapped");
        candidates.forEach(candidate -> equivalent(before, candidate, data()));
    }

    private static void threeTableOrders() {
        PlanNode before = rewritten(THREE);
        List<PlanNode> candidates = candidates(before);
        check(candidates.stream().anyMatch(candidate -> containsJoin(firstJoin(candidate).children().get(1))), "no regrouping");
        candidates.forEach(candidate -> equivalent(before, candidate, data()));
    }

    private static void bushyOrders() {
        PlanNode before = rewritten(FOUR);
        List<PlanNode> candidates = candidates(before);
        check(candidates.stream().anyMatch(candidate -> {
            PlanNode join = firstJoin(candidate);
            return containsJoin(join.children().get(0)) && containsJoin(join.children().get(1));
        }), "no bushy tree");
        candidates.forEach(candidate -> equivalent(before, candidate, data()));
    }

    private static void naturalOrders() {
        PlanNode before = rewritten("SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes");
        List<PlanNode> candidates = candidates(before);
        check(candidates.size() > 1, "natural join was not enumerated");
        candidates.forEach(candidate -> equivalent(before, candidate, data()));
    }

    private static void mixedOrders() {
        PlanNode before = rewritten("SELECT students.name, dept.dname, takes.grade FROM students "
                + "NATURAL JOIN dept JOIN takes ON students.id = takes.id WHERE takes.course_id > 99");
        candidates(before).forEach(candidate -> equivalent(before, candidate, data()));
    }

    private static void duplicateRows() {
        PlanNode before = rewritten("SELECT students.name, students.name FROM students, dept WHERE students.dept_id = dept.dept_id");
        PlanNode after = optimizer().optimize(before);
        equivalent(before, after, data());
        equal(4, eval(after, data()).values().size());
        equal(2, after.outputColumns().size());
    }

    private static void emptyInputs() {
        PlanNode before = rewritten(THREE);
        Map<String, List<List<Object>>> fixture = data();
        fixture.put("takes", List.of());
        candidates(before).forEach(candidate -> equivalent(before, candidate, fixture));
        equal(List.of(), eval(optimizer().optimize(before), fixture).values());
    }

    private static void sortOrder() {
        PlanNode before = rewritten(TWO + " ORDER BY students.id DESC");
        PlanNode after = optimizer().optimize(before);
        equal(eval(before, data()), eval(after, data()));
        equivalent(before, after, data());
    }

    private static void costSelection() {
        PlanNode before = rewritten(TWO);
        CostModel model = new CostModel(catalog());
        PlanNode after = new JoinAccessPathOptimizer(model).optimize(before);
        double minimum = candidates(before).stream().mapToDouble(model::getCost).min().orElseThrow();
        equal(minimum, model.getCost(after));
        check(model.getCost(after) < model.getCost(before), "large outer relation was not improved");
        equivalent(before, after, data());
    }

    private static void stableTies() {
        PlanNode first = bound("SELECT * FROM dept");
        PlanNode second = bound("SELECT * FROM dept");
        check(first != second, "test needs distinct objects");
        check(new PlanSelector().pickCheapest(List.of(first, second), new CostModel(catalog())) == first, "unstable tie");
    }

    private static void budget() {
        PlanNode before = rewritten(FOUR);
        JoinOrderEnumerator enumerator = new JoinOrderEnumerator(7);
        List<PlanNode> first = enumerator.enumerate(before);
        equal(first, enumerator.enumerate(before));
        check(first.size() > 1 && first.size() <= 7, "candidate limit not respected");
        check(first.get(0) == before, "baseline missing");
        try { first.add(before); throw new AssertionError("mutable candidate list"); }
        catch (UnsupportedOperationException expected) { }
    }

    private static void limitBoundary() {
        PlanNode before = rewritten(TWO + " ORDER BY students.name");
        PlanNode limit = PlanNodes.limit(before, new LimitSpec(2));
        limit = new PlanNode(limit.kind(), limit.children(), before.outputColumns(), Optional.empty(),
                limit.scan(), limit.predicate(), limit.projection(), limit.join(), limit.sort(), limit.mutation(),
                limit.createIndex(), limit.limit(), limit.createTable(), limit.dropTable());
        check(new JoinRewriter().cartesianToJoin(limit) == limit, "rewrote below LIMIT");
        List<PlanNode> alternatives = candidates(limit);
        check(alternatives.size() > 1, "join search did not enter LIMIT child");
        for (PlanNode candidate : alternatives) {
            equal(limit.limit(), candidate.limit());
            equal(eval(limit, data()), eval(candidate, data()));
        }
        equal(eval(limit, data()), eval(optimizer().optimize(limit), data()));
    }

    private static void immutability() {
        PlanNode before = rewritten(THREE);
        String snapshot = before.toString();
        PlanNode after = optimizer().optimize(before);
        equal(snapshot, before.toString());
        equal(before.outputColumns(), after.outputColumns());
        equivalent(before, after, data());
    }

    private static void invalidInputs() {
        domainError(() -> optimizer().optimize(null));
        domainError(() -> new JoinRewriter().cartesianToJoin(null));
        domainError(() -> new JoinOrderEnumerator().enumerate(null));
        domainError(() -> new PlanSelector().pickCheapest(List.of(), new CostModel(catalog())));
        domainError(() -> new PlanSelector().pickCheapest(java.util.Arrays.asList((PlanNode) null), new CostModel(catalog())));
        domainError(() -> new PlanSelector().pickCheapest(List.of(bound(TWO)), null));
    }

    private static void subqueryBoundary() {
        PlanNode child = rewritten(TWO);
        Expression subquery = new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.of(new SubquerySpec(SubqueryType.EXISTS, bound("SELECT * FROM dept"), true)));
        PlanNode filter = QO5Plans.filter(child, subquery);
        for (PlanNode candidate : candidates(filter)) {
            equal(subquery, candidate.predicate().orElseThrow());
            equivalent(filter, candidate, data());
        }
        check(new JoinRewriter().mergeSelectionIntoJoin(filter).predicate().orElseThrow().equals(subquery), "moved subquery");
    }

    private static void facadeIntegration() {
        var catalog = catalog();
        QueryOptimizer qo = new QueryOptimizer(catalog);
        ParsedQuery parsed = qo.parseQuery("SELECT students.name, dept.dname FROM students, dept WHERE students.dept_id = dept.dept_id");
        QueryPlan result = qo.optimizeQuery(parsed);
        equal(0, count(result.root(), PlanNodeKind.CARTESIAN));
        equivalent(new Binder(catalog).bind(parsed), result.root(), data());
        equal(qo.getCost(result), result.estimatedCost());
    }

    private static void commandPassThrough() {
        for (String sql : List.of("UPDATE students SET gpa = 3 WHERE id = 1", "DELETE FROM students WHERE id = 1",
                "INSERT INTO students VALUES (4, 'Deni', 3.1, 20)", "CREATE INDEX ix ON students(id)")) {
            PlanNode before = bound(sql);
            check(optimizer().optimize(before) == before, "changed a command outside package one");
        }
    }

    private static void constantPredicates() {
        for (String constant : List.of("1 = 1", "1 = 0")) {
            PlanNode before = bound(TWO + " WHERE " + constant);
            candidates(before).forEach(candidate -> equivalent(before, candidate, data()));
        }
    }

    private static void naturalWithoutCommon() {
        // Binder currently rejects this SQL form. Exercise the valid shared-plan semantics directly
        // without changing QO3: two input schemas with no shared name imply a Cartesian product.
        PlanNode left = bound("SELECT students.name FROM students");
        PlanNode right = bound("SELECT dept.dname FROM dept");
        PlanNode product = QO5Plans.join(left, right, List.of());
        PlanNode natural = new PlanNode(PlanNodeKind.JOIN, product.children(), product.outputColumns(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(),
                Optional.of(new JoinSpec(JoinType.NATURAL, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of())),
                Optional.empty(), Optional.empty(), Optional.empty());
        candidates(natural).forEach(candidate -> equivalent(natural, candidate, data()));
    }

    private static void singleTable() {
        PlanNode before = rewritten("SELECT students.name FROM students WHERE students.id > 1");
        check(optimizer().optimize(before) == before, "unnecessary single-table reconstruction");
    }

    private static void fourNatural() {
        PlanNode before = rewritten("SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes NATURAL JOIN courses");
        candidates(before).forEach(candidate -> equivalent(before, candidate, data()));
    }

    private static void disconnectedInputs() {
        PlanNode before = rewritten("SELECT students.name, dept.dname, courses.title FROM students, dept, courses "
                + "WHERE students.dept_id = dept.dept_id");
        candidates(before).forEach(candidate -> equivalent(before, candidate, data()));
    }

    private static void variedContents() {
        PlanNode before = rewritten(FOUR);
        List<PlanNode> alternatives = candidates(before);
        java.util.Random random = new java.util.Random(3140);
        for (int trial = 0; trial < 12; trial++) {
            Map<String, List<List<Object>>> fixture = data();
            List<List<Object>> students = new java.util.ArrayList<>();
            for (int i = 0; i < random.nextInt(7); i++)
                students.add(List.of(1 + random.nextInt(4), "S" + i, 2.0 + random.nextDouble(),
                        10 * (1 + random.nextInt(4))));
            fixture.put("students", students);
            if (trial % 3 == 0) fixture.put("courses", List.of());
            alternatives.forEach(candidate -> equivalent(before, candidate, fixture));
        }
    }

    private static JoinAccessPathOptimizer optimizer() { return new JoinAccessPathOptimizer(new CostModel(catalog())); }
    private static List<PlanNode> candidates(PlanNode plan) { return new JoinOrderEnumerator().enumerate(plan); }
    private static boolean containsJoin(PlanNode plan) { return firstJoin(plan) != null; }
    private static PlanNode firstJoin(PlanNode plan) {
        if (QO5Plans.isJoin(plan)) return plan;
        for (PlanNode child : plan.children()) {
            PlanNode found = firstJoin(child);
            if (found != null) return found;
        }
        return null;
    }
    private static int count(PlanNode plan, PlanNodeKind kind) {
        return (plan.kind() == kind ? 1 : 0) + plan.children().stream().mapToInt(child -> count(child, kind)).sum();
    }
    private static void domainError(Runnable action) {
        try { action.run(); throw new AssertionError("expected QoException"); }
        catch (QoException expected) { equal(ErrorCode.INVALID_STATE, expected.getInfo().code()); }
    }
    private static void test(String name, Runnable action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (RuntimeException | AssertionError failure) {
            failed++;
            System.out.println("FAIL " + name + ": " + failure);
        }
    }

    // Access paths and projection cleanup

    /** QO-13/QO-14 checks use metadata-only SM and an independent in-memory INDEX_SCAN evaluator. */
    static final class QO5AccessPathTest {
        private QO5AccessPathTest() { }

        static void run(BiConsumer<String, Runnable> test) {
            test.accept("HASH equality uses an index and preserves duplicate keys", QO5AccessPathTest::hashEquality);
            test.accept("HASH never serves range or NE conditions", QO5AccessPathTest::hashRestrictions);
            test.accept("BPLUS_TREE supports each range and reversed operand form", QO5AccessPathTest::treeRanges);
            test.accept("BETWEEN eligibility follows QO3 cardinality and preserves bounds", QO5AccessPathTest::betweenBounds);
            test.accept("contradictory conditions preserve empty results", QO5AccessPathTest::contradictions);
            test.accept("uncovered predicates remain residual filters", QO5AccessPathTest::residuals);
            test.accept("arithmetic predicates and column comparisons are not index lookups", QO5AccessPathTest::unsupportedPredicates);
            test.accept("no matching index falls back to sequential scan", QO5AccessPathTest::missingIndex);
            test.accept("higher-cost index loses to sequential scan", QO5AccessPathTest::expensiveIndex);
            test.accept("all applicable indexes are compared deterministically", QO5AccessPathTest::multipleIndexes);
            test.accept("numeric lookup keys use exact compatible types", QO5AccessPathTest::numericKeys);
            test.accept("inexact numeric key conversions retain scans", QO5AccessPathTest::inexactKeys);
            test.accept("text equality and ranges preserve results", QO5AccessPathTest::textKeys);
            test.accept("empty tables keep full-scan headers and zero rows", QO5AccessPathTest::emptyTable);
            test.accept("index lookup without matches returns an empty result", QO5AccessPathTest::noMatches);
            test.accept("catalog identifiers are matched case-insensitively", QO5AccessPathTest::identifierCase);
            test.accept("projection/filter chains keep SELECT order and ORDER BY keys", QO5AccessPathTest::projectionAndSort);
            test.accept("join and access candidates select the global evaluated minimum", QO5AccessPathTest::combinedMinimum);
            test.accept("access alternatives include combinations across join inputs", QO5AccessPathTest::combinedInputs);
            test.accept("UPDATE/DELETE optimize only target-reading subtrees", QO5AccessPathTest::mutations);
            test.accept("nonjoin facade still applies QO4 rewrites before index selection", QO5AccessPathTest::nonjoinFacade);
            test.accept("index enumeration preserves input and is idempotent on chosen paths", QO5AccessPathTest::immutability);
            test.accept("access path budget includes baseline and stays immutable", QO5AccessPathTest::budget);
            test.accept("LIMIT and subquery predicates survive local access transformations", QO5AccessPathTest::boundaries);
            test.accept("access path dependency and argument validation is explicit", QO5AccessPathTest::validation);
            test.accept("metadata failures propagate without a success-shaped fallback", QO5AccessPathTest::catalogFailure);
            test.accept("NATURAL JOIN keeps deduplicated schema after index selection", QO5AccessPathTest::naturalJoin);
            test.accept("facade preserves mutation payloads with indexed target reads", QO5AccessPathTest::mutationFacade);
            test.accept("catalog snapshots do not leak between optimization calls", QO5AccessPathTest::catalogRefresh);
            test.accept("SELECT star drops only the redundant identity project after index selection", QO5AccessPathTest::identityCleanup);
            test.accept("identity cleanup preserves reordered repeated narrowed and renamed columns", QO5AccessPathTest::nonidentityProjects);
            test.accept("projection cleanup merges, drops identity, reaches below LIMIT and into subqueries", QO5AccessPathTest::projectionCleanup);
            test.accept("identity cleanup is immutable idempotent and stops at LIMIT", QO5AccessPathTest::cleanupBoundaries);
            test.accept("cleanup keeps residual and ORDER BY columns available", QO5AccessPathTest::cleanupRequiredColumns);
            test.accept("final candidate costs include projection cleanup", QO5AccessPathTest::cleanupCostSelection);
            test.accept("indexed DML consumes covered predicates without changing target rows", QO5AccessPathTest::dmlShapes);
            test.accept("indexed DML retains uncovered predicates in child FILTER", QO5AccessPathTest::dmlResidualShapes);
            test.accept("BETWEEN remains available when QO3 estimates agree", QO5AccessPathTest::betweenConsistentEstimates);
        }

        private static void hashEquality() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT students.name FROM students WHERE id = 3");
            PlanNode after = fixture.choose(before);
            equal(1, nodes(after, PlanNodeKind.INDEX_SCAN).size());
            equal(0, nodes(after, PlanNodeKind.FILTER).size());
            equal(2, eval(after, data(), fixture.storage).values().size());
            fixture.equivalent(before, after);
        }

        private static void hashRestrictions() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            for (String predicate : List.of("id > 1", "id >= 1", "id < 3", "id <= 3", "id <> 2")) {
                PlanNode before = rewritten("SELECT * FROM students WHERE " + predicate);
                equal(List.of(before), fixture.paths(before));
            }
        }

        private static void treeRanges() {
            Fixture fixture = sparse(index("students", "id", IndexType.BPLUS_TREE));
            for (String predicate : List.of("id > 1", "id >= 1", "id < 3", "id <= 3", "1 < id", "1 <= id", "3 > id", "3 >= id")) {
                PlanNode before = rewritten("SELECT * FROM students WHERE " + predicate);
                PlanNode after = fixture.choose(before);
                equal(1, nodes(after, PlanNodeKind.INDEX_SCAN).size());
                fixture.equivalent(before, after);
            }
        }

        private static void betweenBounds() {
            Fixture fixture = sparse(index("students", "id", IndexType.BPLUS_TREE));
            for (String lower : List.of(">", ">=")) for (String upper : List.of("<", "<=")) {
                PlanNode before = rewritten("SELECT name FROM students WHERE id " + lower + " 1 AND id " + upper + " 3");
                List<PlanNode> ranges = fixture.paths(before).stream().filter(plan -> nodes(plan, PlanNodeKind.INDEX_SCAN).stream()
                        .anyMatch(scan -> scan.scan().orElseThrow().condition().orElseThrow().operator() == IndexOperator.BETWEEN)).toList();
                PlanNode scan = nodes(before, PlanNodeKind.SCAN).get(0);
                IndexCondition between = new IndexCondition(IndexOperator.BETWEEN, Literals.integer(1),
                        Optional.of(Literals.integer(3)), lower.equals(">="), upper.equals("<="));
                PlanNode probe = new PlanNode(PlanNodeKind.INDEX_SCAN, List.of(), scan.outputColumns(), Optional.empty(),
                        Optional.of(new ScanSpec("students", Optional.of(index("students", "id", IndexType.BPLUS_TREE).indexName()),
                                Optional.of(between))), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty());
                CardinalityEstimator estimator = new CardinalityEstimator(fixture.storage);
                boolean estimatesAgree = estimator.estimateRows(before) == estimator.estimateRows(probe);
                equal(estimatesAgree, !ranges.isEmpty());
                List<PlanNode> singleBounds = fixture.paths(before).stream().filter(plan -> nodes(plan, PlanNodeKind.INDEX_SCAN).stream()
                        .anyMatch(indexScan -> indexScan.scan().orElseThrow().condition().orElseThrow().operator() != IndexOperator.BETWEEN)).toList();
                check(!singleBounds.isEmpty(), "single-bound access alternatives must remain available");
                singleBounds.forEach(candidate -> fixture.equivalent(before, candidate));
                for (PlanNode range : ranges) {
                    IndexCondition condition = nodes(range, PlanNodeKind.INDEX_SCAN).get(0).scan().orElseThrow().condition().orElseThrow();
                    equal(lower.equals(">="), condition.lowerInclusive());
                    equal(upper.equals("<="), condition.upperInclusive());
                    equal(1, condition.key().integerValue().orElseThrow());
                    equal(3, condition.upperBound().orElseThrow().integerValue().orElseThrow());
                    equal(0, nodes(range, PlanNodeKind.FILTER).size());
                    fixture.equivalent(before, range);
                }
            }
        }

        private static void contradictions() {
            Fixture fixture = sparse(index("students", "id", IndexType.BPLUS_TREE));
            for (String predicate : List.of("id > 3 AND id < 1", "id = 1 AND id = 2", "id > 2 AND id <= 2")) {
                PlanNode before = rewritten("SELECT name FROM students WHERE " + predicate);
                fixture.paths(before).forEach(candidate -> fixture.equivalent(before, candidate));
                equal(List.of(), eval(fixture.choose(before), data(), fixture.storage).values());
            }
        }

        private static void residuals() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT name FROM students WHERE id = 3 AND gpa > 3.5");
            PlanNode after = fixture.choose(before);
            equal(1, nodes(after, PlanNodeKind.INDEX_SCAN).size());
            equal(1, nodes(after, PlanNodeKind.FILTER).size());
            fixture.equivalent(before, after);
            // Repeated identical conjuncts must not fail or accidentally retain an extra lookup.
            PlanNode repeated = rewritten("SELECT name FROM students WHERE id = 3 AND id = 3");
            fixture.paths(repeated).forEach(candidate -> fixture.equivalent(repeated, candidate));
        }

        private static void unsupportedPredicates() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.BPLUS_TREE));
            for (String predicate : List.of("id + 1 = 3", "id = dept_id", "id <> 3")) {
                PlanNode before = rewritten("SELECT name FROM students WHERE " + predicate);
                equal(List.of(before), fixture.paths(before));
            }
        }

        private static void missingIndex() {
            Fixture fixture = new Fixture(index("students", "gpa", IndexType.HASH));
            PlanNode before = rewritten("SELECT name FROM students WHERE id = 3");
            check(fixture.choose(before) == before, "used an index on another column");
            Fixture none = new Fixture();
            check(none.choose(before) == before, "no-index fallback changed the plan");
        }

        private static void expensiveIndex() {
            Fixture fixture = new Fixture(index("dept", "dept_id", IndexType.HASH));
            PlanNode before = rewritten("SELECT * FROM dept WHERE dept_id = 10");
            check(fixture.paths(before).size() > 1, "missing index alternative");
            check(nodes(fixture.choose(before), PlanNodeKind.INDEX_SCAN).isEmpty(), "expensive index was selected");
            PlanNode denseRange = rewritten("SELECT name FROM students WHERE id > 1");
            Fixture dense = new Fixture(index("students", "id", IndexType.BPLUS_TREE));
            check(dense.choose(denseRange) == denseRange, "unselective dense range should scan");
        }

        private static void multipleIndexes() {
            IndexMetadata z = new IndexMetadata("z_id", "students", "id", IndexType.HASH);
            IndexMetadata a = new IndexMetadata("a_id", "students", "id", IndexType.BPLUS_TREE);
            Fixture fixture = new Fixture(z, a);
            PlanNode before = rewritten("SELECT name FROM students WHERE id = 3");
            List<PlanNode> paths = fixture.paths(before);
            equal(3, paths.size());
            PlanNode after = fixture.choose(before);
            equal("a_id", nodes(after, PlanNodeKind.INDEX_SCAN).get(0).scan().orElseThrow().indexName().orElseThrow());
            double minimum = paths.stream().mapToDouble(fixture.cost::getCost).min().orElseThrow();
            equal(minimum, fixture.cost.getCost(after));
        }

        private static void numericKeys() {
            for (String query : List.of("SELECT name FROM students WHERE id = 3.0", "SELECT name FROM students WHERE gpa = 3")) {
                String column = query.contains("gpa") ? "gpa" : "id";
                Fixture fixture = new Fixture(index("students", column, IndexType.HASH));
                PlanNode before = rewritten(query);
                List<PlanNode> indexed = fixture.paths(before).stream().filter(plan -> !nodes(plan, PlanNodeKind.INDEX_SCAN).isEmpty()).toList();
                check(!indexed.isEmpty(), "exact numeric conversion was skipped");
                indexed.forEach(candidate -> fixture.equivalent(before, candidate));
            }
        }

        private static void inexactKeys() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.BPLUS_TREE));
            for (String predicate : List.of("id = 2.5", "id > 2.5", "id = 2147483648.0", "id = -0.0")) {
                PlanNode before = rewritten("SELECT name FROM students WHERE " + predicate);
                equal(List.of(before), fixture.paths(before));
            }
        }

        private static void textKeys() {
            Fixture fixture = sparse(index("students", "name", IndexType.BPLUS_TREE));
            for (String predicate : List.of("name = 'Cici'", "name >= 'Bima' AND name < 'D'")) {
                PlanNode before = rewritten("SELECT name FROM students WHERE " + predicate);
                fixture.paths(before).forEach(candidate -> fixture.equivalent(before, candidate));
            }
        }

        private static void emptyTable() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            fixture.statistics.put("students", new TableStatistics(0, 0, 0, 1, Map.of("id", 0L)));
            PlanNode before = rewritten("SELECT name FROM students WHERE id = 3");
            check(fixture.choose(before) == before, "empty table scan should win");
            var empty = data(); empty.put("students", List.of());
            equivalent(before, fixture.choose(before), empty, fixture.storage);
        }

        private static void noMatches() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT name FROM students WHERE id = 99");
            PlanNode after = fixture.choose(before);
            equal(1, nodes(after, PlanNodeKind.INDEX_SCAN).size());
            equal(List.of(), eval(after, data(), fixture.storage).values());
            fixture.equivalent(before, after);
        }

        private static void identifierCase() {
            Fixture fixture = new Fixture(new IndexMetadata("MiXeD", "STUDENTS", "ID", IndexType.HASH));
            PlanNode before = rewritten("SELECT NAME FROM STUDENTS WHERE ID = 3");
            PlanNode after = fixture.choose(before);
            equal(1, nodes(after, PlanNodeKind.INDEX_SCAN).size());
            fixture.equivalent(before, after);
        }

        private static void projectionAndSort() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT name, name FROM students WHERE id = 3 ORDER BY gpa DESC");
            PlanNode after = fixture.choose(before);
            equal(1, nodes(after, PlanNodeKind.INDEX_SCAN).size());
            equal(eval(before, data(), fixture.storage), eval(after, data(), fixture.storage));
            fixture.equivalent(before, after);
        }

        private static void combinedMinimum() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH), index("dept", "dept_id", IndexType.HASH));
            PlanNode before = rewritten("SELECT students.name, dept.dname FROM students, dept "
                    + "WHERE students.dept_id = dept.dept_id AND students.id = 3");
            JoinRewriter rewriter = new JoinRewriter();
            PlanNode rewritten = rewriter.mergeSelectionIntoJoin(rewriter.cartesianToJoin(before));
            List<PlanNode> oracle = new ArrayList<>(); oracle.add(before);
            for (PlanNode order : new JoinOrderEnumerator().enumerate(rewritten)) oracle.addAll(fixture.paths(order));
            PlanNode after = new JoinAccessPathOptimizer(fixture.cost, fixture.storage).optimize(before);
            equal(oracle.stream().mapToDouble(fixture.cost::getCost).min().orElseThrow(), fixture.cost.getCost(after));
            check(fixture.cost.getCost(after) <= fixture.cost.getCost(before), "baseline cost regression");
            equal(1, nodes(after, PlanNodeKind.INDEX_SCAN).size());
            fixture.equivalent(before, after);
        }

        private static void combinedInputs() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH), index("takes", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT students.name, takes.course_id FROM students JOIN takes ON students.id = takes.id "
                    + "WHERE students.id = 3 AND takes.id = 3");
            List<PlanNode> paths = fixture.paths(before);
            check(paths.stream().anyMatch(candidate -> nodes(candidate, PlanNodeKind.INDEX_SCAN).size() == 2), "no combined access paths");
            paths.forEach(candidate -> fixture.equivalent(before, candidate));
            PlanNode chosen = fixture.choose(before);
            equal(paths.stream().mapToDouble(fixture.cost::getCost).min().orElseThrow(), fixture.cost.getCost(chosen));
        }

        private static void mutations() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            for (String sql : List.of("UPDATE students SET gpa = gpa + 0.1 WHERE id = 3", "DELETE FROM students WHERE id = 3")) {
                PlanNode before = new Binder(fixture.storage).bind(new QueryOptimizer(fixture.storage).parseQuery(sql));
                PlanNode after = new JoinAccessPathOptimizer(fixture.cost, fixture.storage).optimize(before);
                equal(before.kind(), after.kind()); equal(before.mutation(), after.mutation());
                equal(1, nodes(after, PlanNodeKind.INDEX_SCAN).size());
                fixture.equivalent(before.children().get(0), after.children().get(0));
            }
        }

        private static void nonjoinFacade() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            QueryOptimizer qo = new QueryOptimizer(fixture.storage);
            ParsedQuery parsed = qo.parseQuery("SELECT name FROM students WHERE id = 3 AND gpa > 3 ORDER BY dept_id");
            PlanNode before = new Binder(fixture.storage).bind(parsed);
            QueryPlan after = qo.optimizeQuery(parsed);
            equal(1, nodes(after.root(), PlanNodeKind.INDEX_SCAN).size());
            equal(qo.getCost(after), after.estimatedCost());
            fixture.equivalent(before, after.root());
        }

        private static void immutability() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT name FROM students WHERE id = 3 AND gpa > 3");
            String snapshot = before.toString();
            PlanNode after = fixture.choose(before);
            equal(snapshot, before.toString()); equal(before.outputColumns(), after.outputColumns());
            check(fixture.choose(after) == after, "selected index path changed on a second call");
        }

        private static void budget() {
            Fixture fixture = new Fixture(new IndexMetadata("z", "students", "id", IndexType.HASH),
                    new IndexMetadata("a", "students", "id", IndexType.BPLUS_TREE));
            AccessPathOptimizer access = new AccessPathOptimizer(fixture.storage, 2);
            PlanNode before = rewritten("SELECT name FROM students WHERE id = 3");
            List<PlanNode> paths = access.enumerateAccessPaths(before);
            equal(2, paths.size()); check(paths.get(0) == before, "baseline not first");
            equal(paths, access.enumerateAccessPaths(before));
            try { paths.add(before); throw new AssertionError("mutable candidates"); }
            catch (UnsupportedOperationException expected) { }
        }

        private static void boundaries() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode child = rewritten("SELECT name FROM students WHERE id = 3");
            PlanNode rawLimit = PlanNodes.limit(child, new LimitSpec(1));
            PlanNode limit = new PlanNode(rawLimit.kind(), rawLimit.children(), child.outputColumns(), rawLimit.estimatedRows(),
                    rawLimit.scan(), rawLimit.predicate(), rawLimit.projection(), rawLimit.join(), rawLimit.sort(),
                    rawLimit.mutation(), rawLimit.createIndex(), rawLimit.limit(), rawLimit.createTable(), rawLimit.dropTable());
            PlanNode indexedLimit = fixture.choose(limit);
            equal(limit.limit(), indexedLimit.limit());
            equal(1, nodes(indexedLimit, PlanNodeKind.INDEX_SCAN).size());
            equal(eval(limit, data(), fixture.storage), eval(indexedLimit, data(), fixture.storage));
            Expression subquery = new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.of(new SubquerySpec(SubqueryType.EXISTS, bound("SELECT * FROM dept"), true)));
            PlanNode filter = QO5Plans.filter(child, subquery);
            PlanNode indexedFilter = fixture.choose(filter);
            equal(ProjectionCleanup.normalize(filter).predicate(), indexedFilter.predicate());
            equal(1, nodes(indexedFilter, PlanNodeKind.INDEX_SCAN).size());
            equivalent(filter, indexedFilter, data(), fixture.storage);
        }

        private static void validation() {
            Fixture fixture = new Fixture();
            domainError(() -> fixture.access.chooseAccessPath(null, fixture.cost));
            domainError(() -> fixture.access.chooseAccessPath(bound("SELECT * FROM students"), null));
            try { new AccessPathOptimizer(null); throw new AssertionError("null storage accepted"); }
            catch (IllegalArgumentException expected) { }
            try { new AccessPathOptimizer(fixture.storage, 1); throw new AssertionError("invalid budget accepted"); }
            catch (IllegalArgumentException expected) { }
        }

        private static void catalogFailure() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            fixture.failIndexes = true;
            domainError(() -> fixture.choose(rewritten("SELECT name FROM students WHERE id = 3")));
        }

        private static void naturalJoin() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH), index("takes", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes WHERE students.id = 3");
            PlanNode after = new JoinAccessPathOptimizer(fixture.cost, fixture.storage).optimize(before);
            check(!nodes(after, PlanNodeKind.INDEX_SCAN).isEmpty(), "no natural join access alternative was selected");
            equal(before.outputColumns(), after.outputColumns());
            fixture.equivalent(before, after);
        }

        private static void mutationFacade() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            QueryOptimizer qo = new QueryOptimizer(fixture.storage);
            for (String sql : List.of("UPDATE students SET gpa = gpa + 0.1 WHERE id = 3", "DELETE FROM students WHERE id = 3")) {
                ParsedQuery parsed = qo.parseQuery(sql);
                PlanNode before = new Binder(fixture.storage).bind(parsed);
                QueryPlan after = qo.optimizeQuery(parsed);
                equal(before.mutation(), after.root().mutation());
                equal(1, nodes(after.root(), PlanNodeKind.INDEX_SCAN).size());
                equal(qo.getCost(after), after.estimatedCost());
                fixture.equivalent(before.children().get(0), after.root().children().get(0));
            }
        }

        private static void catalogRefresh() {
            IndexMetadata[] indexes = { index("students", "id", IndexType.HASH) };
            Fixture fixture = new Fixture(indexes);
            PlanNode before = rewritten("SELECT name FROM students WHERE id = 3");
            equal(1, nodes(fixture.choose(before), PlanNodeKind.INDEX_SCAN).size());
            indexes[0] = index("students", "gpa", IndexType.HASH);
            check(fixture.choose(before) == before, "stale index metadata escaped its invocation");
        }

        private static void identityCleanup() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT * FROM students WHERE id = 3");
            // QO4 already drops the identity PROJECT of SELECT *, so no PROJECT may appear before or after index selection.
            equal(0, nodes(before, PlanNodeKind.PROJECT).size());
            PlanNode after = new JoinAccessPathOptimizer(fixture.cost, fixture.storage).optimize(before);
            equal(PlanNodeKind.INDEX_SCAN, after.kind());
            equal(0, nodes(after, PlanNodeKind.PROJECT).size());
            fixture.equivalent(before, after);
        }

        private static void nonidentityProjects() {
            PlanNode scan = bound("SELECT * FROM students").children().get(0);
            List<ResultColumn> columns = scan.outputColumns();
            List<List<ResultColumn>> layouts = List.of(
                    List.of(columns.get(1), columns.get(0), columns.get(2), columns.get(3)),
                    List.of(columns.get(0), columns.get(0)), List.of(columns.get(1)));
            for (List<ResultColumn> layout : layouts) {
                PlanNode inner = project(scan, layout.stream().map(column -> column.source().orElseThrow()).toList(), layout);
                PlanNode outer = project(inner, inner.projection(), inner.outputColumns());
                check(QO5Plans.simplifyIdentityProjects(outer) == outer, "nonidentity project was removed");
                equivalent(outer, QO5Plans.simplifyIdentityProjects(outer), data());
            }
            List<ResultColumn> renamed = new ArrayList<>(columns);
            renamed.set(0, new ResultColumn("identifier", columns.get(0).source(), columns.get(0).dataType()));
            PlanNode inner = project(scan, columns.stream().map(column -> column.source().orElseThrow()).toList(), renamed);
            PlanNode outer = project(inner, inner.projection(), renamed);
            check(QO5Plans.simplifyIdentityProjects(outer) == outer, "header rename was erased");
            PlanNode unbound = PlanNodes.project(scan, inner.projection());
            PlanNode wrapper = PlanNodes.project(unbound, inner.projection());
            check(QO5Plans.simplifyIdentityProjects(wrapper) == wrapper, "unbound metadata was treated as identity");
        }

        private static void projectionCleanup() {
            PlanNode scan = bound("SELECT * FROM students").children().get(0);
            List<ResultColumn> all = scan.outputColumns();
            List<ColumnRef> allRefs = all.stream().map(column -> column.source().orElseThrow()).toList();
            // PROJECT(name) over PROJECT(name, id) merges into one PROJECT over the scan.
            List<ResultColumn> nameId = List.of(all.get(1), all.get(0));
            PlanNode wide = project(scan, List.of(allRefs.get(1), allRefs.get(0)), nameId);
            PlanNode narrow = project(wide, List.of(allRefs.get(1)), List.of(all.get(1)));
            PlanNode merged = ProjectionCleanup.normalize(narrow);
            equal(PlanNodeKind.PROJECT, merged.kind());
            equal(PlanNodeKind.SCAN, merged.children().get(0).kind());
            equal(List.of(allRefs.get(1)), merged.projection());
            equal(List.of(all.get(1)), merged.outputColumns());
            equivalent(narrow, merged, data());
            // An identical PROJECT disappears, a reordering one stays.
            PlanNode identity = project(scan, allRefs, all);
            equal(scan, ProjectionCleanup.normalize(identity));
            PlanNode reordered = project(scan, List.of(allRefs.get(1), allRefs.get(0), allRefs.get(2), allRefs.get(3)),
                    List.of(all.get(1), all.get(0), all.get(2), all.get(3)));
            check(ProjectionCleanup.normalize(reordered) == reordered, "reordering PROJECT was removed");
            // Cleaning reaches below LIMIT and keeps LIMIT on top; the input plan is not mutated.
            PlanNode inner = project(identity, allRefs, all);
            PlanNode limited = new PlanNode(PlanNodeKind.LIMIT, List.of(inner), inner.outputColumns(), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(new LimitSpec(2)), Optional.empty(), Optional.empty());
            String snapshot = limited.toString();
            PlanNode cleaned = ProjectionCleanup.normalize(limited);
            equal(snapshot, limited.toString());
            equal(PlanNodeKind.LIMIT, cleaned.kind());
            equal(0, nodes(cleaned, PlanNodeKind.PROJECT).size());
            check(ProjectionCleanup.normalize(cleaned) == cleaned, "cleanup is not idempotent");
            equivalent(limited, cleaned, data());
            // Subquery plans are cleaned too; the filter keeps its own predicate otherwise.
            Expression subquery = new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.of(new SubquerySpec(SubqueryType.EXISTS, bound("SELECT * FROM dept"), false)));
            PlanNode filtered = ProjectionCleanup.normalize(QO5Plans.filter(identity, subquery));
            equal(PlanNodeKind.FILTER, filtered.kind());
            equal(PlanNodeKind.SCAN, filtered.children().get(0).kind());
            equal(0, nodes(filtered.predicate().orElseThrow().subquery().orElseThrow().root(), PlanNodeKind.PROJECT).size());
            // Unbound metadata is never merged or treated as identity.
            PlanNode unbound = PlanNodes.project(scan, List.of(allRefs.get(1), allRefs.get(0)));
            PlanNode wrapper = PlanNodes.project(unbound, List.of(allRefs.get(1)));
            check(ProjectionCleanup.normalize(wrapper) == wrapper, "unbound metadata was merged");
        }

        private static void cleanupBoundaries() {
            PlanNode scan = bound("SELECT * FROM students").children().get(0);
            List<ColumnRef> refs = scan.outputColumns().stream().map(column -> column.source().orElseThrow()).toList();
            PlanNode original = project(project(scan, refs, scan.outputColumns()), refs, scan.outputColumns());
            String snapshot = original.toString();
            PlanNode cleaned = QO5Plans.simplifyIdentityProjects(original);
            equal(snapshot, original.toString());
            equal(1, nodes(cleaned, PlanNodeKind.PROJECT).size());
            check(QO5Plans.simplifyIdentityProjects(cleaned) == cleaned, "cleanup is not idempotent");
            equivalent(original, cleaned, data());
            PlanNode limit = PlanNodes.limit(original, new LimitSpec(0));
            check(QO5Plans.simplifyIdentityProjects(limit) == limit, "cleanup crossed LIMIT");
            Expression subquery = new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.of(new SubquerySpec(SubqueryType.EXISTS, bound("SELECT * FROM dept"), true)));
            PlanNode correlated = QO5Plans.filter(original, subquery);
            check(QO5Plans.simplifyIdentityProjects(correlated) == correlated, "cleanup crossed a subquery boundary");
        }

        private static void cleanupRequiredColumns() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT name, name FROM students WHERE id = 3 AND gpa > 3 ORDER BY dept_id");
            PlanNode after = new JoinAccessPathOptimizer(fixture.cost, fixture.storage).optimize(before);
            fixture.equivalent(before, after);
            equal(eval(before, data(), fixture.storage), eval(after, data(), fixture.storage));
            equal(2, after.outputColumns().size());
            equal(1, nodes(after, PlanNodeKind.FILTER).size());
        }

        private static void cleanupCostSelection() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            PlanNode before = rewritten("SELECT * FROM students JOIN dept ON students.dept_id = dept.dept_id WHERE students.id = 3");
            JoinRewriter rewriter = new JoinRewriter();
            PlanNode rewritten = rewriter.mergeSelectionIntoJoin(rewriter.cartesianToJoin(before));
            List<PlanNode> oracle = new ArrayList<>(); oracle.add(QO5Plans.simplifyIdentityProjects(before));
            for (PlanNode order : new JoinOrderEnumerator().enumerate(rewritten)) {
                oracle.add(QO5Plans.simplifyIdentityProjects(order));
                fixture.paths(order).forEach(path -> oracle.add(QO5Plans.simplifyIdentityProjects(path)));
            }
            PlanNode after = new JoinAccessPathOptimizer(fixture.cost, fixture.storage).optimize(before);
            equal(oracle.stream().mapToDouble(fixture.cost::getCost).min().orElseThrow(), fixture.cost.getCost(after));
            fixture.equivalent(before, after);
        }

        private static void dmlShapes() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            QueryOptimizer optimizer = new QueryOptimizer(fixture.storage);
            for (String sql : List.of("DELETE FROM students WHERE id = 3", "UPDATE students SET gpa = gpa + 0.1 WHERE id = 3")) {
                ParsedQuery parsed = optimizer.parseQuery(sql);
                PlanNode before = new Binder(fixture.storage).bind(parsed);
                PlanNode after = optimizer.optimizeQuery(parsed).root();
                equal(before.kind(), after.kind()); equal(before.mutation(), after.mutation());
                equal(PlanNodeKind.INDEX_SCAN, after.children().get(0).kind());
                equal(0, nodes(after, PlanNodeKind.FILTER).size());
                equal(before.children().get(0).outputColumns(), after.children().get(0).outputColumns());
                fixture.equivalent(before.children().get(0), after.children().get(0));
                equal(2, eval(after.children().get(0), data(), fixture.storage).values().size());
            }
        }

        private static void dmlResidualShapes() {
            Fixture fixture = new Fixture(index("students", "id", IndexType.HASH));
            QueryOptimizer optimizer = new QueryOptimizer(fixture.storage);
            for (String command : List.of("DELETE FROM students", "UPDATE students SET gpa = gpa + 0.1")) {
                for (String threshold : List.of("3.0", "3.5")) {
                    ParsedQuery parsed = optimizer.parseQuery(command + " WHERE id = 3 AND gpa > " + threshold);
                    PlanNode before = new Binder(fixture.storage).bind(parsed);
                    PlanNode after = optimizer.optimizeQuery(parsed).root();
                    equal(before.mutation(), after.mutation());
                    equal(PlanNodeKind.FILTER, after.children().get(0).kind());
                    equal(PlanNodeKind.INDEX_SCAN, after.children().get(0).children().get(0).kind());
                    fixture.equivalent(before.children().get(0), after.children().get(0));
                }
                PlanNode all = optimizer.optimizeQuery(optimizer.parseQuery(command)).root();
                equal(PlanNodeKind.SCAN, all.children().get(0).kind());
            }
        }

        private static void betweenConsistentEstimates() {
            Fixture fixture = sparse(index("students", "id", IndexType.BPLUS_TREE));
            fixture.statistics.put("students", new TableStatistics(1, 1000, 40, 1, Map.of("id", 1L)));
            PlanNode before = rewritten("SELECT name FROM students WHERE id >= 1 AND id <= 3");
            List<PlanNode> ranges = fixture.paths(before).stream().filter(plan -> nodes(plan, PlanNodeKind.INDEX_SCAN).stream()
                    .anyMatch(scan -> scan.scan().orElseThrow().condition().orElseThrow().operator() == IndexOperator.BETWEEN)).toList();
            check(!ranges.isEmpty(), "a consistent BETWEEN estimate was unnecessarily blocked");
            CardinalityEstimator estimator = new CardinalityEstimator(fixture.storage);
            for (PlanNode candidate : ranges) {
                equal(estimator.estimateRows(before), estimator.estimateRows(candidate));
                fixture.equivalent(before, candidate);
            }
        }

        private static PlanNode project(PlanNode child, List<ColumnRef> refs, List<ResultColumn> outputs) {
            return new PlanNode(PlanNodeKind.PROJECT, List.of(child), List.copyOf(outputs), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.copyOf(refs), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        private static Fixture sparse(IndexMetadata index) {
            Fixture fixture = new Fixture(index);
            fixture.statistics.put("students", new TableStatistics(4, 1000, 40, 1,
                    Map.of("id", 3L, "name", 3L, "gpa", 3L, "dept_id", 2L)));
            return fixture;
        }

        private static IndexMetadata index(String table, String column, IndexType type) {
            return new IndexMetadata(table + "_" + column + "_" + type, table, column, type);
        }

        private static List<PlanNode> nodes(PlanNode plan, PlanNodeKind kind) {
            List<PlanNode> result = new ArrayList<>();
            if (plan.kind() == kind) result.add(plan);
            plan.children().forEach(child -> result.addAll(nodes(child, kind)));
            return result;
        }

        private static void domainError(Runnable action) {
            try { action.run(); throw new AssertionError("expected domain error"); }
            catch (QoException expected) { equal(ErrorCode.INVALID_STATE, expected.getInfo().code()); }
        }

        private static final class Fixture {
            final Map<String, TableStatistics> statistics = new HashMap<>();
            final IStorageManager storage;
            final CostModel cost;
            final AccessPathOptimizer access;
            boolean failIndexes;

            Fixture(IndexMetadata... definitions) {
                statistics.put("students", MockStorageManager.largeStatistics("students"));
                statistics.put("takes", MockStorageManager.largeStatistics("takes"));
                IStorageManager base = catalog();
                storage = (IStorageManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IStorageManager.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "listTables" -> base.listTables();
                            case "getSchema" -> base.getSchema((String) args[0]);
                            case "getStatistics" -> {
                                String table = ((String) args[0]).toLowerCase(Locale.ROOT);
                                yield statistics.containsKey(table) ? statistics.get(table) : base.getStatistics(table);
                            }
                            case "getIndexes" -> {
                                if (failIndexes) throw QoErrors.invalidState("catalog unavailable");
                                String table = (String) args[0];
                                check(base.listTables().contains(table), "catalog needs canonical table spelling");
                                yield java.util.Arrays.stream(definitions).filter(index -> index.tableName().equalsIgnoreCase(table)).toList();
                            }
                            case "getIndexStatistics" -> MockStorageManager.defaultIndexStatistics(java.util.Arrays.stream(definitions)
                                    .filter(index -> index.indexName().equalsIgnoreCase((String) args[0])).findFirst()
                                    .orElseThrow(() -> new AssertionError("statistics requested for unknown index " + args[0])));
                            default -> throw new AssertionError("optimizer must not perform physical I/O: " + method.getName());
                        });
                cost = new CostModel(storage);
                access = new AccessPathOptimizer(storage);
            }

            PlanNode choose(PlanNode plan) { return access.chooseAccessPath(plan, cost); }
            List<PlanNode> paths(PlanNode plan) { return access.enumerateAccessPaths(plan); }
            void equivalent(PlanNode before, PlanNode after) { QO5TestSupport.equivalent(before, after, data(), storage); }
        }
    }

    // Genetic join search

    /** QO-18 and alias/LIMIT regressions, registered in the existing QO5 suite. */
    static final class QO5GeneticTest {
        private QO5GeneticTest() { }

        static final String FOUR = "SELECT s.name, d.dname, c.title FROM students AS s "
                + "JOIN dept AS d ON s.dept_id = d.dept_id JOIN takes AS t ON s.id = t.id "
                + "JOIN courses AS c ON t.course_id = c.course_id";

        static void run(BiConsumer<String, Runnable> test) {
            test.accept("GA config validates population generations and mutation rate", QO5GeneticTest::configuration);
            test.accept("OX crossover and mutation always preserve permutation validity", QO5GeneticTest::geneticOperators);
            test.accept("heuristic and GA share an equivalent decoder for every join shape", QO5GeneticTest::sharedDecoder);
            test.accept("GA population and generations perform valid cost-based search", QO5GeneticTest::geneticSearch);
            test.accept("GA preserves the best cost across generations", QO5GeneticTest::elitism);
            test.accept("GA seed produces repeatable per-call results", QO5GeneticTest::reproducibility);
            test.accept("GA permits large populations on small permutation spaces", QO5GeneticTest::smallSpace);
            test.accept("GA zero generations still evaluates its initial population", QO5GeneticTest::zeroGenerations);
            test.accept("GA mutation rates zero and one keep equivalent plans", QO5GeneticTest::mutationExtremes);
            test.accept("QO5 compares genetic and heuristic costs with the same access paths", QO5GeneticTest::comparison);
            test.accept("QO5 keeps heuristic mode as the default", QO5GeneticTest::heuristicDefault);
            test.accept("GA evaluates enclosing sort costs and preserves final order", QO5GeneticTest::sortContext);
            test.accept("GA works for natural joins and preserves merged-column representatives", QO5GeneticTest::naturalJoin);
            test.accept("GA preserves Cartesian products constants and duplicate rows", QO5GeneticTest::cartesianAndConstants);
            test.accept("GA handles empty input data without changing headers", QO5GeneticTest::emptyInput);
            test.accept("alias index lookup uses physical catalog names and retains aliases", QO5GeneticTest::aliasIndex);
            test.accept("two aliases of the same table remain distinct join inputs", QO5GeneticTest::selfJoin);
            test.accept("alias natural join remains valid with indexed input", QO5GeneticTest::aliasNaturalIndex);
            test.accept("LIMIT zero and positive preserve their nodes while GA searches children", QO5GeneticTest::limits);
            test.accept("subquery predicates stay in scope while GA searches local joins", QO5GeneticTest::subqueries);
            test.accept("GA leaves input plans and returned history immutable", QO5GeneticTest::immutability);
            test.accept("QueryOptimizer opt-in GA is never costlier, deterministic, valid and off by default", QO5GeneticTest::facadeGa);
            test.accept("QueryOptimizer reports heuristic and GA costs for a join search", QO5GeneticTest::facadeComparison);
            test.accept("buffer blocks option lowers block nested loop cost", QO5GeneticTest::bufferBlocksOption);
            test.accept("QODriver parses buffer-blocks and GA options and prints a comparison", QO5GeneticTest::driverOptions);
            test.accept("GA validates missing plans and cost models", QO5GeneticTest::validation);
        }

        private static GeneticOptimizerConfig config() { return new GeneticOptimizerConfig(12, 6, 0.4, 3140); }

        private static void configuration() {
            invalid(() -> new GeneticOptimizerConfig(1, 1, 0.1, 1));
            invalid(() -> new GeneticOptimizerConfig(2, -1, 0.1, 1));
            for (double rate : List.of(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY))
                invalid(() -> new GeneticOptimizerConfig(2, 1, rate, 1));
            equal(32, GeneticOptimizerConfig.defaults().populationSize());
        }

        private static void geneticOperators() {
            Random random = new Random(99);
            for (int size = 2; size <= 9; size++) {
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < size; i++) order.add(i);
                var first = new GeneticJoinOrderOptimizer.Individual(order, JoinSearchSpace.Shape.LEFT);
                for (int iteration = 0; iteration < 100; iteration++) {
                    Collections.shuffle(order, random);
                    var second = new GeneticJoinOrderOptimizer.Individual(order, JoinSearchSpace.Shape.BUSHY);
                    var child = GeneticJoinOrderOptimizer.crossover(first, second, random);
                    var mutated = GeneticJoinOrderOptimizer.mutate(child, random);
                    equal(size, new java.util.HashSet<>(child.order()).size());
                    equal(size, new java.util.HashSet<>(mutated.order()).size());
                    check(!child.order().equals(mutated.order()), "swap mutation did not swap distinct genes");
                    first = mutated;
                }
            }
        }

        private static void sharedDecoder() {
            PlanNode before = rewritten(FOUR);
            JoinSearchSpace space = JoinSearchSpace.from(before).orElseThrow();
            Random random = new Random(100);
            List<Integer> order = new ArrayList<>(space.originalOrder());
            for (int i = 0; i < 24; i++) {
                Collections.shuffle(order, random);
                for (JoinSearchSpace.Shape shape : JoinSearchSpace.Shape.values())
                    equivalent(before, space.decode(order, shape), data());
            }
            domain(() -> space.decode(List.of(0, 0, 1, 2), JoinSearchSpace.Shape.LEFT));
            domain(() -> space.decode(List.of(0, 1), JoinSearchSpace.Shape.LEFT));
        }

        private static void geneticSearch() {
            PlanNode before = rewritten("SELECT students.name, dept.dname FROM students JOIN dept ON students.dept_id = dept.dept_id");
            CostModel cost = new CostModel(catalog());
            var result = new GeneticJoinOrderOptimizer(config()).optimize(before, cost);
            check(result.evaluatedIndividuals() > 1, "GA did not evaluate alternatives");
            equal(config().generations(), result.generationsRun());
            equal(cost.getCost(result.plan()), result.estimatedCost());
            check(result.estimatedCost() < cost.getCost(before), "GA did not improve the expensive input order");
            equivalent(before, result.plan(), data());
        }

        private static void elitism() {
            var result = new GeneticJoinOrderOptimizer(config()).optimize(rewritten(FOUR), new CostModel(catalog()));
            equal(config().generations() + 1, result.bestCosts().size());
            for (int i = 1; i < result.bestCosts().size(); i++)
                check(result.bestCosts().get(i) <= result.bestCosts().get(i - 1), "best cost regressed");
            equal(result.estimatedCost(), result.bestCosts().get(result.bestCosts().size() - 1));
        }

        private static void reproducibility() {
            PlanNode before = rewritten(FOUR);
            GeneticJoinOrderOptimizer ga = new GeneticJoinOrderOptimizer(config());
            var first = ga.optimize(before, new CostModel(catalog()));
            var second = ga.optimize(before, new CostModel(catalog()));
            equal(first, second);
        }

        private static void smallSpace() {
            PlanNode before = rewritten("SELECT s.name, d.dname FROM students AS s JOIN dept AS d ON s.dept_id = d.dept_id");
            var result = new GeneticJoinOrderOptimizer(new GeneticOptimizerConfig(128, 4, 1, 1))
                    .optimize(before, new CostModel(catalog()));
            check(result.evaluatedIndividuals() <= 6, "equivalent chromosomes were not cached");
            equal(4, result.generationsRun());
            equivalent(before, result.plan(), data());
        }

        private static void zeroGenerations() {
            var result = new GeneticJoinOrderOptimizer(new GeneticOptimizerConfig(4, 0, 0, 42))
                    .optimize(rewritten(FOUR), new CostModel(catalog()));
            equal(0, result.generationsRun());
            equal(1, result.bestCosts().size());
            check(result.evaluatedIndividuals() > 0 && result.evaluatedIndividuals() <= 4, "initial population not bounded");
        }

        private static void mutationExtremes() {
            PlanNode before = rewritten(FOUR);
            for (double rate : List.of(0.0, 1.0)) {
                for (long seed : List.of(1L, 2L, 3L)) {
                    var ga = new GeneticJoinOrderOptimizer(new GeneticOptimizerConfig(8, 3, rate, seed));
                    equivalent(before, ga.optimize(before, new CostModel(catalog())).plan(), data());
                }
            }
        }

        private static void comparison() {
            IStorageManager storage = indexedCatalog();
            CostModel cost = new CostModel(storage);
            PlanNode before = rewritten(FOUR + " WHERE s.id = 3");
            var report = new JoinAccessPathOptimizer(cost, storage, config()).optimizeWithComparison(before);
            double heuristic = cost.getCost(new JoinAccessPathOptimizer(cost, storage).optimize(before));
            equal(heuristic, report.heuristicCost());
            check(report.geneticCost().isPresent(), "GA comparison not reported");
            equal(Math.min(report.heuristicCost(), report.geneticCost().orElseThrow()), report.estimatedCost());
            equal(cost.getCost(report.plan()), report.estimatedCost());
            check(!nodes(report.plan(), PlanNodeKind.INDEX_SCAN).isEmpty(), "fitness ignored index alternatives");
            equivalent(before, report.plan(), data(), storage);
        }

        private static void heuristicDefault() {
            var report = new JoinAccessPathOptimizer(new CostModel(catalog()), catalog())
                    .optimizeWithComparison(rewritten(FOUR));
            check(report.geneticCost().isEmpty(), "default unexpectedly enabled GA");
            equal(0, report.evaluatedIndividuals());
            equal(report.estimatedCost(), report.heuristicCost());
        }

        private static void sortContext() {
            PlanNode before = rewritten("SELECT s.name, d.dname FROM students AS s JOIN dept AS d "
                    + "ON s.dept_id = d.dept_id ORDER BY s.id DESC");
            CostModel cost = new CostModel(catalog());
            var result = new GeneticJoinOrderOptimizer(config()).optimize(before, cost);
            equal(cost.getCost(result.plan()), result.estimatedCost());
            equal(eval(before, data()), eval(result.plan(), data()));
        }

        private static void naturalJoin() {
            PlanNode before = rewritten("SELECT * FROM students AS s NATURAL JOIN dept AS d NATURAL JOIN takes AS t");
            var result = new GeneticJoinOrderOptimizer(config()).optimize(before, new CostModel(catalog()));
            equivalent(before, result.plan(), data());
        }

        private static void cartesianAndConstants() {
            for (String condition : List.of("1 = 1", "1 = 0")) {
                PlanNode before = rewritten("SELECT s.name, d.dname FROM students AS s, dept AS d WHERE " + condition);
                var result = new GeneticJoinOrderOptimizer(config()).optimize(before, new CostModel(catalog()));
                equivalent(before, result.plan(), data());
            }
        }

        private static void emptyInput() {
            PlanNode before = rewritten(FOUR);
            var result = new GeneticJoinOrderOptimizer(config()).optimize(before, new CostModel(catalog()));
            Map<String, List<List<Object>>> rows = data(); rows.put("takes", List.of());
            equivalent(before, result.plan(), rows);
        }

        private static void aliasIndex() {
            IStorageManager storage = indexedCatalog();
            for (String sql : List.of("SELECT s.name FROM students AS s WHERE s.id = 3",
                    "SELECT S.NAME FROM STUDENTS AS S WHERE S.ID = 3")) {
                PlanNode before = rewritten(sql);
                PlanNode after = new JoinAccessPathOptimizer(new CostModel(storage), storage, config()).optimize(before);
                PlanNode scan = nodes(after, PlanNodeKind.INDEX_SCAN).get(0);
                equal("students", scan.scan().orElseThrow().tableName());
                check(scan.scan().orElseThrow().alias().isPresent(), "alias was dropped");
                equivalent(before, after, data(), storage);
            }
        }

        private static void selfJoin() {
            PlanNode before = rewritten("SELECT s.name, t.name FROM students AS s JOIN students AS t "
                    + "ON s.dept_id = t.dept_id WHERE s.id <> t.id");
            IStorageManager storage = indexedCatalog();
            var report = new JoinAccessPathOptimizer(new CostModel(storage), storage, config()).optimizeWithComparison(before);
            equivalent(before, report.plan(), data(), storage);
            List<PlanNode> scans = new ArrayList<>(nodes(report.plan(), PlanNodeKind.SCAN));
            scans.addAll(nodes(report.plan(), PlanNodeKind.INDEX_SCAN));
            equal(2, scans.size());
            equal(2L, scans.stream().map(scan -> scan.scan().orElseThrow().alias().orElseThrow()).distinct().count());
        }

        private static void aliasNaturalIndex() {
            IStorageManager storage = indexedCatalog();
            PlanNode before = rewritten("SELECT * FROM students AS s NATURAL JOIN takes AS t WHERE s.id = 3");
            PlanNode after = new JoinAccessPathOptimizer(new CostModel(storage), storage, config()).optimize(before);
            check(!nodes(after, PlanNodeKind.INDEX_SCAN).isEmpty(), "no indexed natural-join input");
            equivalent(before, after, data(), storage);
        }

        private static void limits() {
            IStorageManager storage = indexedCatalog();
            for (int count : List.of(0, 2)) {
                PlanNode before = rewritten(FOUR + " WHERE s.id = 3 ORDER BY s.name LIMIT " + count);
                var report = new JoinAccessPathOptimizer(new CostModel(storage), storage, config()).optimizeWithComparison(before);
                equal(PlanNodeKind.LIMIT, report.plan().kind());
                equal(before.limit(), report.plan().limit());
                check(report.evaluatedIndividuals() > 0, "GA did not search LIMIT child");
                check(!nodes(report.plan(), PlanNodeKind.INDEX_SCAN).isEmpty(), "LIMIT blocked index selection");
                equal(eval(before, data(), storage), eval(report.plan(), data(), storage));
            }
        }

        private static void subqueries() {
            PlanNode child = rewritten(FOUR);
            Expression sub = new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(),
                    List.of(), Optional.of(new SubquerySpec(SubqueryType.EXISTS, bound("SELECT * FROM dept"), false)));
            PlanNode before = QO5Plans.filter(child, sub);
            var result = new GeneticJoinOrderOptimizer(config()).optimize(before, new CostModel(catalog()));
            check(result.evaluatedIndividuals() > 0, "subquery predicate blocked local joins");
            equal(sub, result.plan().predicate().orElseThrow());
            equivalent(before, result.plan(), data());
        }

        private static void facadeGa() {
            IStorageManager storage = catalog();
            QueryOptimizer heuristic = new QueryOptimizer(storage);
            QueryOptimizer explicitOff = new QueryOptimizer(storage, 3, null);
            QueryOptimizer ga = new QueryOptimizer(storage, 3, config());
            for (String sql : List.of(FOUR, FOUR + " WHERE s.id = 3", "SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes",
                    "SELECT s.name FROM students AS s, dept AS d, takes AS t WHERE s.dept_id = d.dept_id AND s.id = t.id")) {
                QueryPlan base = heuristic.optimizeQuery(heuristic.parseQuery(sql));
                // Default behavior is unchanged by the new constructor.
                equal(base.root(), explicitOff.optimizeQuery(explicitOff.parseQuery(sql)).root());
                equal(base.estimatedCost(), explicitOff.optimizeQuery(explicitOff.parseQuery(sql)).estimatedCost());
                QueryPlan searched = ga.optimizeQuery(ga.parseQuery(sql));
                PlanNodes.validate(searched.root());
                check(searched.estimatedCost() <= base.estimatedCost() + 1e-9, "GA facade must not return a costlier plan: " + sql);
                equal(searched.estimatedCost(), ga.getCost(searched));
                equal(base.root().outputColumns(), searched.root().outputColumns());
                equivalent(bound(sql), searched.root(), data(), storage);
                // Same seed, same plan.
                equal(searched.root(), new QueryOptimizer(storage, 3, config()).optimizeQuery(ga.parseQuery(sql)).root());
            }
        }

        private static void facadeComparison() {
            IStorageManager storage = catalog();
            var off = new QueryOptimizer(storage).compareJoinSearch(FOUR);
            check(off.geneticCost().isEmpty(), "heuristic facade must not run the GA");
            equal(0, off.evaluatedIndividuals());
            var on = new QueryOptimizer(storage, 3, config()).compareJoinSearch(FOUR);
            check(on.geneticCost().isPresent(), "GA facade must report a GA cost");
            check(on.evaluatedIndividuals() > 0, "GA must evaluate individuals");
            equal(off.heuristicCost(), on.heuristicCost());
            check(on.estimatedCost() <= on.heuristicCost() + 1e-9, "selected cost must not exceed heuristic cost");
            try { new QueryOptimizer(storage, 3, config()).compareJoinSearch("DELETE FROM students"); throw new AssertionError("expected INVALID_STATE"); }
            catch (QoException expected) { equal(ErrorCode.INVALID_STATE, expected.getInfo().code()); }
            invalid(() -> new QueryOptimizer(storage, 2, config()));
        }

        private static void bufferBlocksOption() {
            IStorageManager storage = catalog();
            String sql = "SELECT * FROM students JOIN takes ON students.id = takes.id";
            double m3 = new QueryOptimizer(storage, 3).optimizeQuery(new QueryOptimizer(storage).parseQuery(sql)).estimatedCost();
            double m10 = new QueryOptimizer(storage, 10).optimizeQuery(new QueryOptimizer(storage).parseQuery(sql)).estimatedCost();
            check(m10 < m3, "a larger buffer must lower the block nested loop cost: " + m3 + " vs " + m10);
        }

        private static void driverOptions() {
            var none = QODriver.Options.parse(new String[] { "--explain", "SELECT 1" }, 2);
            equal(3, none.bufferBlocks());
            check(none.genetic().isEmpty(), "GA must be off unless requested");
            var ga = QODriver.Options.parse(new String[] { "--compare", "q", "--ga" }, 2);
            equal(GeneticOptimizerConfig.defaults(), ga.genetic().orElseThrow());
            var tuned = QODriver.Options.parse(new String[] { "--explain", "q", "--population", "8", "--generations", "4",
                    "--mutation-rate", "0.5", "--seed", "7", "--buffer-blocks", "11" }, 2);
            equal(new GeneticOptimizerConfig(8, 4, 0.5, 7), tuned.genetic().orElseThrow());
            equal(11, tuned.bufferBlocks());
            for (String[] bad : List.of(new String[] { "--bogus", "1" }, new String[] { "--population" }, new String[] { "--population", "x" },
                    new String[] { "--buffer-blocks", "2" }, new String[] { "--population", "1" }))
                invalid(() -> QODriver.Options.parse(prepend(bad), 2));
            var compare = QODriver.Options.parse(new String[] { "--compare", "q", "--population", "6", "--generations", "3" }, 2);
            String report = compare.compare(compare.newOptimizer(MockStorageManager.create()),
                    "SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes");
            check(report.contains("Heuristic cost:") && report.contains("GA cost:") && report.contains("Evaluated individuals:"), report);
        }

        private static String[] prepend(String[] options) {
            String[] all = new String[options.length + 2];
            all[0] = "--explain";
            all[1] = "q";
            System.arraycopy(options, 0, all, 2, options.length);
            return all;
        }

        private static void immutability() {
            PlanNode before = rewritten(FOUR);
            String snapshot = before.toString();
            var result = new GeneticJoinOrderOptimizer(config()).optimize(before, new CostModel(catalog()));
            equal(snapshot, before.toString());
            try { result.bestCosts().add(1.0); throw new AssertionError("mutable history"); }
            catch (UnsupportedOperationException expected) { }
            var individual = new GeneticJoinOrderOptimizer.Individual(List.of(0, 1), JoinSearchSpace.Shape.LEFT);
            try { individual.order().set(0, 1); throw new AssertionError("mutable chromosome"); }
            catch (UnsupportedOperationException expected) { }
        }

        private static void validation() {
            GeneticJoinOrderOptimizer ga = new GeneticJoinOrderOptimizer(config());
            domain(() -> ga.optimize(null, new CostModel(catalog())));
            invalid(() -> ga.optimize(rewritten(FOUR), null));
            invalid(() -> new GeneticJoinOrderOptimizer.Individual(List.of(0, 0), JoinSearchSpace.Shape.LEFT));
        }

        static IStorageManager indexedCatalog() {
            IStorageManager base = catalog();
            return (IStorageManager) Proxy.newProxyInstance(QO5GeneticTest.class.getClassLoader(), new Class<?>[] { IStorageManager.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "listTables" -> base.listTables();
                        case "getSchema" -> base.getSchema((String) args[0]);
                        case "getStatistics" -> {
                            TableStatistics large = MockStorageManager.largeStatistics((String) args[0]);
                            yield large != null ? large : base.getStatistics((String) args[0]);
                        }
                        case "getIndexes" -> {
                            String table = (String) args[0];
                            check(base.listTables().contains(table), "alias leaked into physical catalog access");
                            yield table.equals("students") ? List.of(new IndexMetadata("students_id", table, "id", IndexType.HASH))
                                    : List.<IndexMetadata>of();
                        }
                        case "getIndexStatistics" -> {
                            check("students_id".equalsIgnoreCase((String) args[0]), "statistics requested for unknown index " + args[0]);
                            yield MockStorageManager.defaultIndexStatistics(new IndexMetadata("students_id", "students", "id", IndexType.HASH));
                        }
                        default -> throw new AssertionError("GA must not perform record I/O: " + method.getName());
                    });
        }

        private static List<PlanNode> nodes(PlanNode plan, PlanNodeKind kind) {
            List<PlanNode> result = new ArrayList<>();
            if (plan.kind() == kind) result.add(plan);
            plan.children().forEach(child -> result.addAll(nodes(child, kind)));
            return result;
        }
        private static void invalid(Runnable action) {
            try { action.run(); throw new AssertionError("expected invalid argument"); }
            catch (IllegalArgumentException expected) { }
        }
        private static void domain(Runnable action) {
            try { action.run(); throw new AssertionError("expected QO error"); }
            catch (QoException expected) { equal(ErrorCode.INVALID_STATE, expected.getInfo().code()); }
        }
    }

    // LIMIT and subquery scopes

    /** Audit regressions exercise actual bound scopes with an independent test-only evaluator. */
    static final class QO5ScopeTest {
        private QO5ScopeTest() { }

        private static final GeneticOptimizerConfig GA = new GeneticOptimizerConfig(8, 3, 0.4, 3140);

        static void run(BiConsumer<String, Runnable> test) {
            test.accept("audit: indexed LIMIT 0 and LIMIT 1 retain payload and ordered results", QO5ScopeTest::indexedLimits);
            test.accept("audit: filters above LIMIT never become index predicates below it", QO5ScopeTest::filterAboveLimit);
            test.accept("audit: Cartesian conversion and join search work below nested LIMIT", QO5ScopeTest::joinsBelowLimit);
            test.accept("audit: uncorrelated EXISTS optimizes inner and outer indexed reads", QO5ScopeTest::exists);
            test.accept("audit: IN subplans retain duplicates and empty-result semantics", QO5ScopeTest::in);
            test.accept("audit: scalar subplans retain single-row and empty-result semantics", QO5ScopeTest::scalar);
            test.accept("audit: correlated self-subquery retains outer references and local index", QO5ScopeTest::correlated);
            test.accept("audit: correlated subqueries optimize independent local join regions", QO5ScopeTest::correlatedJoin);
            test.accept("audit: nested subqueries preserve nearest and grandparent scopes", QO5ScopeTest::nested);
            test.accept("audit: aliases shadowed by an inner scope remain separate", QO5ScopeTest::shadowedAliases);
            test.accept("audit: dynamic outer keys do not become literal index lookups", QO5ScopeTest::dynamicKey);
            test.accept("audit: subqueries in ON retain join conditions and inner optimization", QO5ScopeTest::joinCondition);
            test.accept("audit: DML subquery optimization preserves target and mutation payload", QO5ScopeTest::mutations);
            test.accept("audit: LIMIT inside a subquery retains its local scope", QO5ScopeTest::subqueryLimit);
            test.accept("audit: scoped GA comparison is reproducible and includes nested evaluations", QO5ScopeTest::comparison);
            test.accept("audit: facade keeps QO4 rewrites compatible with scoped QO5 search", QO5ScopeTest::facade);
        }

        private static IStorageManager storage() { return QO5GeneticTest.indexedCatalog(); }

        private static PlanNode bind(String sql, IStorageManager storage) {
            return new Binder(storage).bind(new QueryOptimizer(storage).parseQuery(sql));
        }

        private static JoinAccessPathOptimizer optimizer(IStorageManager storage, boolean ga) {
            CostModel cost = new CostModel(storage);
            return ga ? new JoinAccessPathOptimizer(cost, storage, GA) : new JoinAccessPathOptimizer(cost, storage);
        }

        private static PlanNode verify(String sql) {
            IStorageManager storage = storage();
            PlanNode before = bind(sql, storage);
            String snapshot = before.toString();
            PlanNode last = null;
            for (boolean ga : List.of(false, true)) {
                PlanNode after = optimizer(storage, ga).optimize(before);
                equivalent(before, after, data(), storage);
                equal(before.outputColumns(), after.outputColumns());
                List<SubquerySpec> oldScopes = subqueries(before), newScopes = subqueries(after);
                equal(oldScopes.size(), newScopes.size());
                for (int i = 0; i < oldScopes.size(); i++) {
                    equal(oldScopes.get(i).type(), newScopes.get(i).type());
                    equal(oldScopes.get(i).correlated(), newScopes.get(i).correlated());
                    equal(oldScopes.get(i).root().outputColumns(), newScopes.get(i).root().outputColumns());
                }
                check(new CostModel(storage).getCost(after) <= new CostModel(storage).getCost(before), "lost cost baseline");
                equal(snapshot, before.toString());
                last = after;
            }
            return last;
        }

        private static void indexedLimits() {
            for (int count : List.of(0, 1)) {
                PlanNode after = verify("SELECT s.name FROM students AS s WHERE s.id = 3 ORDER BY s.name LIMIT " + count);
                equal(PlanNodeKind.LIMIT, after.kind());
                equal((long) count, after.limit().orElseThrow().count());
                check(count(after, PlanNodeKind.INDEX_SCAN) > 0, "LIMIT blocked index access");
            }
        }

        private static void filterAboveLimit() {
            IStorageManager storage = storage();
            PlanNode limited = bind("SELECT * FROM students AS s ORDER BY s.id LIMIT 1", storage);
            PlanNode before = QO5Plans.filter(limited, expression("s.id = 3"));
            for (boolean ga : List.of(false, true)) {
                PlanNode after = optimizer(storage, ga).optimize(before);
                equal(PlanNodeKind.FILTER, after.kind());
                equal(PlanNodeKind.LIMIT, after.children().get(0).kind());
                equal(0, count(after.children().get(0), PlanNodeKind.INDEX_SCAN));
                equal(eval(before, data(), storage), eval(after, data(), storage));
                equal(0, eval(after, data(), storage).values().size());
            }
        }

        private static void joinsBelowLimit() {
            IStorageManager storage = storage();
            PlanNode before = bind("SELECT s.name, d.dname FROM students AS s, dept AS d "
                    + "WHERE s.dept_id = d.dept_id ORDER BY s.name LIMIT 3", storage);
            PlanNode raw = PlanNodes.limit(before, new LimitSpec(2));
            PlanNode nested = new PlanNode(raw.kind(), raw.children(), before.outputColumns(), raw.estimatedRows(), raw.scan(),
                    raw.predicate(), raw.projection(), raw.join(), raw.sort(), raw.mutation(), raw.createIndex(), raw.limit(),
                    raw.createTable(), raw.dropTable());
            for (boolean ga : List.of(false, true)) {
                PlanNode after = optimizer(storage, ga).optimize(nested);
                equal(2, count(after, PlanNodeKind.LIMIT));
                equal(0, count(after, PlanNodeKind.CARTESIAN));
                equal(eval(nested, data(), storage), eval(after, data(), storage));
            }
        }

        private static void exists() {
            PlanNode after = verify("SELECT s.name FROM students AS s WHERE s.id = 3 "
                    + "AND EXISTS (SELECT t.id FROM students AS t WHERE t.id = 1)");
            check(count(after, PlanNodeKind.INDEX_SCAN) >= 2, "inner or outer index missing");
        }

        private static void in() {
            for (int id : List.of(3, 99)) {
                PlanNode after = verify("SELECT s.name FROM students AS s WHERE s.id IN "
                        + "(SELECT t.id FROM students AS t WHERE t.id = " + id + ")");
                check(count(subqueries(after).get(0).root(), PlanNodeKind.INDEX_SCAN) > 0, "IN subplan was skipped");
                equal(id == 3 ? 2 : 0, eval(after, data(), storage()).values().size());
            }
        }

        private static void scalar() {
            for (int id : List.of(1, 99)) {
                PlanNode after = verify("SELECT s.name FROM students AS s WHERE s.id = "
                        + "(SELECT t.id FROM students AS t WHERE t.id = " + id + ")");
                check(count(subqueries(after).get(0).root(), PlanNodeKind.INDEX_SCAN) > 0, "scalar subplan was skipped");
                equal(id == 1 ? 1 : 0, eval(after, data(), storage()).values().size());
            }
        }

        private static void correlated() {
            PlanNode after = verify("SELECT s.name FROM students AS s WHERE EXISTS "
                    + "(SELECT t.id FROM students AS t WHERE t.dept_id = s.dept_id AND t.id = 1)");
            SubquerySpec sub = subqueries(after).get(0);
            check(sub.correlated(), "correlation flag lost");
            check(count(sub.root(), PlanNodeKind.INDEX_SCAN) > 0, "correlated local literal index missing");
            equal(3, eval(after, data(), storage()).values().size());
        }

        private static void correlatedJoin() {
            PlanNode after = verify("SELECT s.name FROM students AS s WHERE EXISTS (SELECT t.id "
                    + "FROM students AS t, dept AS d WHERE t.dept_id = d.dept_id AND d.dept_id = s.dept_id AND t.id = 1)");
            PlanNode sub = subqueries(after).get(0).root();
            equal(0, count(sub, PlanNodeKind.CARTESIAN));
            check(count(sub, PlanNodeKind.INDEX_SCAN) > 0, "correlated join blocked local access paths");
        }

        private static void nested() {
            verify("SELECT s.name FROM students AS s WHERE EXISTS (SELECT t.id FROM students AS t WHERE t.id = 1 "
                    + "AND EXISTS (SELECT d.dept_id FROM dept AS d WHERE d.dept_id = s.dept_id AND d.dept_id = t.dept_id))");
        }

        private static void shadowedAliases() {
            PlanNode after = verify("SELECT s.name FROM students AS s WHERE EXISTS "
                    + "(SELECT s.id FROM students AS s WHERE s.id = 1)");
            check(!subqueries(after).get(0).correlated(), "shadowed local alias became correlated");
            equal(4, eval(after, data(), storage()).values().size());
        }

        private static void dynamicKey() {
            PlanNode after = verify("SELECT s.name FROM students AS s WHERE EXISTS "
                    + "(SELECT t.id FROM students AS t WHERE t.id = s.id)");
            equal(0, count(subqueries(after).get(0).root(), PlanNodeKind.INDEX_SCAN));
        }

        private static void joinCondition() {
            PlanNode after = verify("SELECT s.name, d.dname FROM students AS s JOIN dept AS d "
                    + "ON s.dept_id = d.dept_id AND EXISTS (SELECT t.id FROM students AS t WHERE t.id = 1)");
            check(count(subqueries(after).get(0).root(), PlanNodeKind.INDEX_SCAN) > 0, "ON subplan not optimized");
        }

        private static void mutations() {
            IStorageManager storage = storage();
            for (String sql : List.of("UPDATE students SET gpa = (SELECT t.gpa FROM students AS t WHERE t.id = 1) WHERE id = 3",
                    "DELETE FROM students WHERE EXISTS (SELECT t.id FROM students AS t WHERE t.id = 1)")) {
                PlanNode before = bind(sql, storage);
                PlanNode after = optimizer(storage, true).optimize(before);
                PlanNodes.validate(after);
                equal(before.kind(), after.kind());
                equal(before.mutation().orElseThrow().tableName(), after.mutation().orElseThrow().tableName());
                equal(before.mutation().orElseThrow().updateColumn(), after.mutation().orElseThrow().updateColumn());
                equal(before.outputColumns(), after.outputColumns());
                check(count(subqueries(after).get(0).root(), PlanNodeKind.INDEX_SCAN) > 0, "DML subplan not optimized");
            }
        }

        private static void subqueryLimit() {
            PlanNode after = verify("SELECT s.name FROM students AS s WHERE EXISTS "
                    + "(SELECT t.id FROM students AS t WHERE t.id = 3 ORDER BY t.id LIMIT 1)");
            PlanNode sub = subqueries(after).get(0).root();
            equal(PlanNodeKind.LIMIT, sub.kind());
            check(count(sub, PlanNodeKind.INDEX_SCAN) > 0, "subquery LIMIT blocked index access");
        }

        private static void comparison() {
            IStorageManager storage = storage();
            PlanNode before = bind("SELECT s.name FROM students AS s WHERE EXISTS (SELECT t.name FROM students AS t "
                    + "JOIN dept AS d ON t.dept_id = d.dept_id WHERE t.id = 1)", storage);
            JoinAccessPathOptimizer ga = optimizer(storage, true);
            var first = ga.optimizeWithComparison(before);
            equal(first, ga.optimizeWithComparison(before));
            equal(new CostModel(storage).getCost(optimizer(storage, false).optimize(before)), first.heuristicCost());
            equal(Math.min(first.heuristicCost(), first.geneticCost().orElseThrow()), first.estimatedCost());
            equal(new CostModel(storage).getCost(first.plan()), first.estimatedCost());
            check(first.evaluatedIndividuals() > 0, "nested GA evaluations not reported");
            equivalent(before, first.plan(), data(), storage);
        }

        private static void facade() {
            IStorageManager storage = storage();
            for (String sql : List.of(
                    "SELECT s.name FROM students AS s WHERE s.id = 3 ORDER BY s.name LIMIT 1",
                    "SELECT s.name FROM students AS s WHERE EXISTS (SELECT t.id FROM students AS t "
                            + "WHERE t.dept_id = s.dept_id AND t.id = 1)",
                    "SELECT s.name FROM students AS s WHERE s.id IN (SELECT t.id FROM students AS t WHERE t.id = 3)")) {
                PlanNode before = bind(sql, storage);
                for (boolean ga : List.of(false, true)) {
                    QueryOptimizer qo = ga ? new QueryOptimizer(storage, 3, GA) : new QueryOptimizer(storage);
                    QueryPlan after = qo.optimizeQuery(qo.parseQuery(sql));
                    equivalent(before, after.root(), data(), storage);
                    equal(qo.getCost(after), after.estimatedCost());
                    equal(after.estimatedCost(), qo.compareJoinSearch(sql).estimatedCost());
                    check(count(after.root(), PlanNodeKind.INDEX_SCAN) > 0, "facade lost scoped index optimization");
                }
            }
        }

        private static int count(PlanNode plan, PlanNodeKind kind) {
            int result = plan.kind() == kind ? 1 : 0;
            for (PlanNode child : plan.children()) result += count(child, kind);
            for (SubquerySpec sub : ownSubqueries(plan)) result += count(sub.root(), kind);
            return result;
        }

        private static List<SubquerySpec> subqueries(PlanNode plan) {
            List<SubquerySpec> result = new ArrayList<>();
            for (SubquerySpec sub : ownSubqueries(plan)) {
                result.add(sub);
                result.addAll(subqueries(sub.root()));
            }
            plan.children().forEach(child -> result.addAll(subqueries(child)));
            return result;
        }

        private static List<SubquerySpec> ownSubqueries(PlanNode plan) {
            List<SubquerySpec> result = new ArrayList<>();
            plan.predicate().ifPresent(value -> collect(value, result));
            plan.join().flatMap(JoinSpec::condition).ifPresent(value -> collect(value, result));
            plan.mutation().flatMap(MutationSpec::updateExpression).ifPresent(value -> collect(value, result));
            return result;
        }

        private static void collect(Expression expression, List<SubquerySpec> result) {
            expression.subquery().ifPresent(result::add);
            expression.operands().forEach(operand -> collect(operand, result));
        }
    }

    // Dynamic programming join search

    /** DP is checked against exhaustive labeled binary trees, not the bounded heuristic enumerator. */
    static final class QO5DynamicProgrammingTest {
        private QO5DynamicProgrammingTest() { }

        static void run(BiConsumer<String, Runnable> test) {
            test.accept("DP: configuration and required inputs are validated", QO5DynamicProgrammingTest::validation);
            test.accept("DP: wide subset search matches exhaustive three-table trees", () -> oracle(3));
            test.accept("DP: wide subset search matches all 120 four-table trees", () -> oracle(4));
            test.accept("DP: asymmetric BNLJ selects a cheaper outer input", QO5DynamicProgrammingTest::orientation);
            test.accept("DP: NATURAL joins and repeated output columns preserve representatives", QO5DynamicProgrammingTest::natural);
            test.accept("DP: disconnected inputs and multi-input predicates remain equivalent", QO5DynamicProgrammingTest::disconnected);
            test.accept("DP: constants duplicate rows and empty inputs preserve results", QO5DynamicProgrammingTest::contents);
            test.accept("DP: five and six inputs fit the default evaluation budget", QO5DynamicProgrammingTest::largerRegions);
            test.accept("DP: oversized regions retain a valid heuristic fallback", QO5DynamicProgrammingTest::sizeFallback);
            test.accept("DP: exhausted budgets never expose partial subset plans", QO5DynamicProgrammingTest::budget);
            test.accept("DP: results and counters are reproducible without input mutation", QO5DynamicProgrammingTest::reproducibility);
            test.accept("DP: full pipeline compares DP with heuristic and indexed baselines", QO5DynamicProgrammingTest::pipeline);
            test.accept("DP: enclosing SORT and LIMIT retain complete-plan costs and results", QO5DynamicProgrammingTest::wrappers);
            test.accept("DP: single-table input does not create a join search", QO5DynamicProgrammingTest::singleTable);
            test.accept("DP: catalog errors propagate rather than masquerading as a fallback", QO5DynamicProgrammingTest::catalogFailure);
        }

        private static void validation() {
            for (int relations : List.of(1, 13)) invalid(() -> new DynamicProgrammingJoinOptimizer(relations, 4, 100));
            for (int width : List.of(0, 33)) invalid(() -> new DynamicProgrammingJoinOptimizer(6, width, 100));
            invalid(() -> new DynamicProgrammingJoinOptimizer(6, 4, 1));
            invalid(() -> new DynamicProgrammingJoinOptimizer().optimize(bound("SELECT * FROM students"), null));
            try {
                new DynamicProgrammingJoinOptimizer().optimize(null, new CostModel(catalog()));
                throw new AssertionError("missing plan accepted");
            } catch (QoException expected) { equal(ErrorCode.INVALID_STATE, expected.getInfo().code()); }
        }

        private static void oracle(int size) {
            IStorageManager storage = fixture();
            PlanNode before = bind(query(size), storage);
            List<PlanNode> leaves = new ArrayList<>();
            for (int i = 0; i < size; i++) leaves.add(ProjectionCleanup.normalize(bind("SELECT * FROM t" + i, storage)));
            List<PlanNode> trees = allTrees(leaves, (1 << size) - 1);
            equal(size == 3 ? 12 : 120, trees.size());
            CostModel cost = new CostModel(storage);
            double minimum = cost.getCost(before);
            for (PlanNode tree : trees) {
                PlanNode candidate = QO5Plans.restore(tree, before);
                equivalent(before, candidate, rows(size), storage);
                minimum = Math.min(minimum, cost.getCost(candidate));
            }
            var result = new DynamicProgrammingJoinOptimizer(6, 32, 20_000).optimize(before, cost);
            equal(minimum, result.estimatedCost());
            equal(1, result.optimizedRegions());
            check(!result.budgetExhausted(), "small oracle search exceeded budget");
            equivalent(before, result.plan(), rows(size), storage);
        }

        /** Exhaustive recursion generates every ordered labeled binary tree for a fixed chain graph. */
        private static List<PlanNode> allTrees(List<PlanNode> leaves, int mask) {
            if (Integer.bitCount(mask) == 1) return List.of(leaves.get(Integer.numberOfTrailingZeros(mask)));
            List<PlanNode> result = new ArrayList<>();
            for (int left = 1; left < mask; left++) {
                if ((left & mask) != left) continue;
                int right = mask ^ left;
                List<Expression> edges = new ArrayList<>();
                for (int i = 0; i < leaves.size() - 1; i++) {
                    int pair = (1 << i) | (1 << (i + 1));
                    if ((mask & pair) == pair && ((left >> i) & 1) != ((left >> (i + 1)) & 1))
                        edges.add(expression("t" + i + ".id = t" + (i + 1) + ".id"));
                }
                for (PlanNode a : allTrees(leaves, left)) for (PlanNode b : allTrees(leaves, right))
                    result.add(QO5Plans.join(a, b, edges));
            }
            return result;
        }

        private static void orientation() {
            PlanNode before = bound("SELECT students.name, dept.dname FROM students JOIN dept ON students.dept_id = dept.dept_id");
            CostModel cost = new CostModel(catalog());
            var result = new DynamicProgrammingJoinOptimizer().optimize(before, cost);
            check(result.estimatedCost() < cost.getCost(before), "DP did not change an expensive outer input");
            equivalent(before, result.plan(), data());
        }

        private static void natural() {
            for (String sql : List.of("SELECT * FROM students AS s NATURAL JOIN dept AS d NATURAL JOIN takes AS t",
                    "SELECT s.name, t.name, s.name FROM students AS s JOIN students AS t ON s.dept_id = t.dept_id")) {
                PlanNode before = bound(sql);
                var result = new DynamicProgrammingJoinOptimizer().optimize(before, new CostModel(catalog()));
                equivalent(before, result.plan(), data());
                equal(before.outputColumns(), result.plan().outputColumns());
            }
        }

        private static void disconnected() {
            for (String sql : List.of("SELECT s.name, d.dname, t.grade FROM students AS s, dept AS d, takes AS t WHERE s.id = t.id",
                    "SELECT s.name, d.dname, t.grade FROM students AS s, dept AS d, takes AS t WHERE s.id + t.id = d.dept_id")) {
                PlanNode before = bound(sql);
                equivalent(before, new DynamicProgrammingJoinOptimizer().optimize(before, new CostModel(catalog())).plan(), data());
            }
        }

        private static void contents() {
            for (String condition : List.of("1 = 1", "1 = 0")) {
                PlanNode before = bound("SELECT s.name, d.dname FROM students AS s, dept AS d WHERE " + condition);
                PlanNode after = new DynamicProgrammingJoinOptimizer().optimize(before, new CostModel(catalog())).plan();
                equivalent(before, after, data());
                var empty = data(); empty.put("dept", List.of());
                equivalent(before, after, empty);
            }
        }

        private static void largerRegions() {
            IStorageManager storage = fixture();
            for (int size : List.of(5, 6)) {
                PlanNode before = bind(query(size), storage);
                var result = new DynamicProgrammingJoinOptimizer().optimize(before, new CostModel(storage));
                equal(1, result.optimizedRegions());
                equal(0, result.skippedRegions());
                check(!result.budgetExhausted() && result.evaluatedCandidates() <= 20_000, "default DP budget exceeded");
                equivalent(before, result.plan(), rows(size), storage);
            }
        }

        private static void sizeFallback() {
            IStorageManager storage = fixture();
            PlanNode before = bind(query(7), storage);
            var result = new DynamicProgrammingJoinOptimizer().optimize(before, new CostModel(storage));
            equal(0, result.evaluatedCandidates());
            equal(1, result.skippedRegions());
            check(!result.budgetExhausted(), "size cutoff was reported as evaluation exhaustion");
            equivalent(before, result.plan(), rows(7), storage);
            var qo = new JoinAccessPathOptimizer(new CostModel(storage), storage);
            PlanNode after = qo.optimize(before);
            check(new CostModel(storage).getCost(after) <= result.estimatedCost(), "heuristic fallback lost baseline");
            equivalent(before, after, rows(7), storage);
        }

        private static void budget() {
            IStorageManager storage = fixture();
            PlanNode before = bind(query(3), storage);
            for (int budget : List.of(2, 10)) {
                var result = new DynamicProgrammingJoinOptimizer(6, 4, budget).optimize(before, new CostModel(storage));
                equal(budget, result.evaluatedCandidates());
                check(result.budgetExhausted(), "budget exhaustion not reported");
                equal(1, result.skippedRegions());
                equivalent(before, result.plan(), rows(3), storage);
            }
        }

        private static void reproducibility() {
            IStorageManager storage = fixture();
            PlanNode before = bind(query(4), storage);
            String snapshot = before.toString();
            var dp = new DynamicProgrammingJoinOptimizer();
            var first = dp.optimize(before, new CostModel(storage));
            equal(first, dp.optimize(before, new CostModel(storage)));
            equal(snapshot, before.toString());
        }

        private static void pipeline() {
            IStorageManager storage = QO5GeneticTest.indexedCatalog();
            PlanNode before = bind("SELECT s.name, d.dname, t.grade FROM students AS s, dept AS d, takes AS t "
                    + "WHERE s.dept_id = d.dept_id AND s.id = t.id AND s.id = 3", storage);
            CostModel cost = new CostModel(storage);
            PlanNode rewritten = new JoinRewriter().cartesianToJoin(before);
            var access = new AccessPathOptimizer(storage);
            var dp = new DynamicProgrammingJoinOptimizer().optimize(rewritten, cost,
                    raw -> ProjectionCleanup.normalize(access.chooseAccessPath(raw, cost)));
            var candidates = new ArrayList<PlanNode>(); candidates.add(dp.plan()); candidates.add(before);
            for (PlanNode heuristic : new JoinOrderEnumerator().enumerate(rewritten))
                candidates.add(access.chooseAccessPath(heuristic, cost));
            var report = new JoinAccessPathOptimizer(cost, storage).optimizeWithComparison(before);
            equal(candidates.stream().mapToDouble(cost::getCost).min().orElseThrow(), report.estimatedCost());
            check(hasIndex(report.plan()), "DP pipeline lost index access");
            equivalent(before, report.plan(), data(), storage);
            for (PlanNode leaf : scans(report.plan()))
                equal(leaf.scan().orElseThrow().tableName().equals("students") ? "s" :
                        leaf.scan().orElseThrow().tableName().equals("dept") ? "d" : "t", leaf.scan().orElseThrow().alias().orElseThrow());
        }

        private static void wrappers() {
            IStorageManager storage = fixture();
            PlanNode before = bind(query(4) + " ORDER BY t0.id LIMIT 3", storage);
            CostModel cost = new CostModel(storage);
            var result = new DynamicProgrammingJoinOptimizer().optimize(before, cost);
            equal(before.limit(), result.plan().limit());
            equal(cost.getCost(result.plan()), result.estimatedCost());
            equal(eval(before, rows(4), storage), eval(result.plan(), rows(4), storage));
            equal(1, result.optimizedRegions());
        }

        private static void singleTable() {
            PlanNode before = bound("SELECT name FROM students");
            var result = new DynamicProgrammingJoinOptimizer().optimize(before, new CostModel(catalog()));
            equal(0, result.evaluatedCandidates());
            equal(0, result.optimizedRegions());
            equivalent(before, result.plan(), data());
        }

        private static void catalogFailure() {
            IStorageManager unavailable = (IStorageManager) Proxy.newProxyInstance(QO5DynamicProgrammingTest.class.getClassLoader(),
                    new Class<?>[] { IStorageManager.class }, (proxy, method, args) -> { throw new IllegalStateException("catalog offline"); });
            try {
                new DynamicProgrammingJoinOptimizer().optimize(bound("SELECT * FROM students JOIN dept ON students.dept_id = dept.dept_id"),
                        new CostModel(unavailable));
                throw new AssertionError("catalog failure was hidden");
            } catch (IllegalStateException expected) { equal("catalog offline", expected.getMessage()); }
        }

        private static String query(int size) {
            StringBuilder sql = new StringBuilder("SELECT * FROM t0");
            for (int i = 1; i < size; i++) sql.append(" JOIN t").append(i).append(" ON t").append(i - 1)
                    .append(".id = t").append(i).append(".id");
            return sql.toString();
        }

        private static PlanNode bind(String sql, IStorageManager storage) {
            return new Binder(storage).bind(new QueryOptimizer(storage).parseQuery(sql));
        }

        private static IStorageManager fixture() {
            List<String> tables = List.of("t0", "t1", "t2", "t3", "t4", "t5", "t6");
            long[] blocks = { 100, 1, 20, 2, 500, 4, 10 };
            return (IStorageManager) Proxy.newProxyInstance(QO5DynamicProgrammingTest.class.getClassLoader(),
                    new Class<?>[] { IStorageManager.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "listTables" -> tables;
                        case "getSchema" -> new TableSchema((String) args[0], (String) args[0], List.of(
                                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                                new ColumnSchema("v", DataType.INTEGER, Optional.empty())));
                        case "getStatistics" -> {
                            int index = tables.indexOf((String) args[0]);
                            long records = blocks[index] * 100;
                            yield new TableStatistics(records, blocks[index], 40, 100, Map.of("id", records, "v", records));
                        }
                        case "getIndexes" -> List.<IndexMetadata>of();
                        default -> throw new AssertionError("DP must not perform record I/O: " + method.getName());
                    });
        }

        private static Map<String, List<List<Object>>> rows(int size) {
            Map<String, List<List<Object>>> rows = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) rows.put("t" + i, List.of(List.of(1, i), List.of(2, i), List.of(2, i)));
            return rows;
        }

        private static List<PlanNode> scans(PlanNode node) {
            List<PlanNode> result = new ArrayList<>();
            if (node.scan().isPresent()) result.add(node);
            node.children().forEach(child -> result.addAll(scans(child)));
            return result;
        }

        private static boolean hasIndex(PlanNode node) {
            return scans(node).stream().anyMatch(leaf -> leaf.kind() == PlanNodeKind.INDEX_SCAN);
        }

        private static void invalid(Runnable action) {
            try { action.run(); throw new AssertionError("invalid argument accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    // Reproducible search comparisons

    /** Fixed fixtures demonstrate both GA wins and losses without changing production search or QO3. */
    static final class QO5JoinSearchBenchmarkTest {
        private QO5JoinSearchBenchmarkTest() { }

        static void run(BiConsumer<String, Runnable> test) {
            test.accept("benchmark: GA improves the 128-candidate heuristic on five tables", () -> improvement(5));
            test.accept("benchmark: GA improves the 128-candidate heuristic on six tables", () -> improvement(6));
            test.accept("benchmark: GA can lose and pipeline still chooses the lowest cost", QO5JoinSearchBenchmarkTest::losingCase);
            test.accept("benchmark: fixed seeds reproduce plans costs and search counts", QO5JoinSearchBenchmarkTest::reproducibility);
            test.accept("benchmark: GA configuration controls actual search effort", QO5JoinSearchBenchmarkTest::configuration);
            test.accept("benchmark: all methods use the same indexed catalog and access policy", QO5JoinSearchBenchmarkTest::indexed);
            test.accept("benchmark: every reported cost uses the requested buffer capacity", QO5JoinSearchBenchmarkTest::buffers);
            test.accept("benchmark: standalone driver reports independent costs and fixture inputs", QO5JoinSearchBenchmarkTest::driver);
            test.accept("benchmark: standalone driver keeps custom SQL and GA options", QO5JoinSearchBenchmarkTest::customSql);
            test.accept("benchmark: invalid options and fixture sizes are rejected", QO5JoinSearchBenchmarkTest::validation);
        }

        private static PlanNode input(QO5BenchmarkCatalog.Scenario scenario) {
            var storage = scenario.storage();
            PlanNode bound = new Binder(storage).bind(new QueryOptimizer(storage).parseQuery(scenario.sql()));
            return new SelectionProjectionRewriter(new CardinalityEstimator(storage)).rewrite(bound);
        }

        private static QO5JoinSearchBenchmark.Comparison compare(QO5BenchmarkCatalog.Scenario scenario) {
            return QO5JoinSearchBenchmark.compare(input(scenario), scenario.storage(), 3, GeneticOptimizerConfig.defaults());
        }

        private static void improvement(int tables) {
            var scenario = QO5BenchmarkCatalog.scenario(tables, QO5BenchmarkCatalog.defaultSeed(tables), false);
            var report = compare(scenario);
            verify(scenario, report, 3);
            check(report.measurements().get(2).cost() < report.measurements().get(0).cost(), "GA did not improve the fixed fixture");
            // DP is deliberately reported even where it beats GA; these cases do not claim GA is optimal.
            check(report.measurements().get(1).cost() < report.measurements().get(2).cost(), "DP comparison was omitted or altered");
            equal(128, report.measurements().get(0).candidates());
        }

        private static void losingCase() {
            var scenario = QO5BenchmarkCatalog.scenario(6, 0, false);
            var report = compare(scenario);
            verify(scenario, report, 3);
            check(report.measurements().get(2).cost() > report.measurements().get(0).cost(), "expected the documented GA loss");
            check(report.selectedCost() <= report.measurements().get(0).cost(), "costlier GA result was forced into selection");
        }

        private static void verify(QO5BenchmarkCatalog.Scenario scenario, QO5JoinSearchBenchmark.Comparison report, int buffers) {
            PlanNode before = input(scenario);
            CostModel cost = new CostModel(scenario.storage(), buffers);
            equal(List.of("Heuristic (128)", "DP (6/4/20000)", "GA"), report.measurements().stream().map(QO5JoinSearchBenchmark.Measurement::method).toList());
            for (var method : report.measurements()) {
                equal(cost.getCost(method.plan()), method.cost());
                check(method.elapsedNanos() >= 0, "invalid search duration");
                check(method.cost() <= cost.getCost(before), "method lost the common baseline");
                equivalent(before, method.plan(), scenario.sampleRows(), scenario.storage());
            }
            check(report.measurements().get(0).candidates() <= 129, "heuristic exceeded its independent candidate budget");
            check(report.measurements().get(1).candidates() <= 20_000, "DP exceeded its candidate budget");
            check(report.measurements().get(2).candidates() <= 32 * 21, "GA exceeded its configured chromosome budget");
            equal(report.measurements().stream().mapToDouble(QO5JoinSearchBenchmark.Measurement::cost).min().orElseThrow(), report.selectedCost());
            equal(cost.getCost(report.selected()), report.selectedCost());
            var pipeline = new JoinAccessPathOptimizer(cost, scenario.storage(), GeneticOptimizerConfig.defaults()).optimizeWithComparison(before);
            equal(report.selectedCost(), pipeline.estimatedCost());
            equivalent(before, pipeline.plan(), scenario.sampleRows(), scenario.storage());
        }

        private static void reproducibility() {
            var scenario = QO5BenchmarkCatalog.scenario(5, 3, false);
            PlanNode before = input(scenario);
            String snapshot = before.toString();
            var first = QO5JoinSearchBenchmark.compare(before, scenario.storage(), 3, GeneticOptimizerConfig.defaults());
            var second = QO5JoinSearchBenchmark.compare(before, scenario.storage(), 3, GeneticOptimizerConfig.defaults());
            for (int i = 0; i < 3; i++) {
                var a = first.measurements().get(i); var b = second.measurements().get(i);
                equal(a.plan(), b.plan()); equal(a.cost(), b.cost()); equal(a.candidates(), b.candidates());
            }
            equal(snapshot, before.toString());
            try { first.measurements().clear(); throw new AssertionError("mutable comparison results"); }
            catch (UnsupportedOperationException expected) { }
            try { scenario.statistics().clear(); throw new AssertionError("mutable catalog statistics"); }
            catch (UnsupportedOperationException expected) { }
        }

        private static void configuration() {
            var scenario = QO5BenchmarkCatalog.scenario(5, 3, false);
            PlanNode before = input(scenario);
            var initial = QO5JoinSearchBenchmark.compare(before, scenario.storage(), 3, new GeneticOptimizerConfig(32, 0, 0.15, 3140));
            var evolved = compare(scenario);
            check(initial.measurements().get(2).candidates() <= 32, "zero generations evaluated offspring");
            check(evolved.measurements().get(2).candidates() > initial.measurements().get(2).candidates(), "GA options did not affect search effort");
            check(evolved.measurements().get(2).cost() <= initial.measurements().get(2).cost(), "GA elitism lost the initial best plan");
        }

        private static void indexed() {
            var scenario = QO5BenchmarkCatalog.scenario(6, 4, true);
            var report = compare(scenario);
            verify(scenario, report, 3);
            PlanNode single = new Binder(scenario.storage()).bind(new QueryOptimizer(scenario.storage())
                    .parseQuery("SELECT * FROM t2 WHERE id = 2"));
            check(new AccessPathOptimizer(scenario.storage()).enumerateAccessPaths(single).stream().anyMatch(QO5JoinSearchBenchmarkTest::hasIndex),
                    "fixture did not offer the same index alternative");
            // Validate every index that was actually selected, even if another method chose a scan.
            for (var method : report.measurements()) checkIndexes(method.plan());
        }

        private static boolean hasIndex(PlanNode plan) {
            return plan.kind() == PlanNodeKind.INDEX_SCAN || plan.children().stream().anyMatch(QO5JoinSearchBenchmarkTest::hasIndex);
        }

        private static void checkIndexes(PlanNode plan) {
            if (plan.kind() == PlanNodeKind.INDEX_SCAN) {
                ScanSpec scan = plan.scan().orElseThrow();
                equal("t2", scan.tableName()); equal("t2_id", scan.indexName().orElseThrow());
                equal(IndexOperator.EQ, scan.condition().orElseThrow().operator());
                equal(2, scan.condition().orElseThrow().key().integerValue().orElseThrow());
            }
            plan.children().forEach(QO5JoinSearchBenchmarkTest::checkIndexes);
        }

        private static void buffers() {
            var scenario = QO5BenchmarkCatalog.scenario(5, 3, false);
            var report = QO5JoinSearchBenchmark.compare(input(scenario), scenario.storage(), 8, GeneticOptimizerConfig.defaults());
            verify(scenario, report, 8);
            check(report.measurements().get(0).cost() < compare(scenario).measurements().get(0).cost(), "buffer setting was ignored");
        }

        private static String output(String... args) {
            var bytes = new ByteArrayOutputStream();
            try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) { QO5GeneticDriver.run(args, out); }
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private static void driver() {
            String text = output("--benchmark");
            for (String fragment : List.of("Case: 5 tables; statisticsSeed=3", "Case: 6 tables; statisticsSeed=4",
                    "Heuristic (128)", "DP (6/4/20000)", "GA |", "optimize_ms", "GA improvement vs heuristic:",
                    "seed=3140", "not SQL execution time", "Illustrative synthetic fixtures", "t0: rows="))
                check(text.contains(fragment), "driver omitted " + fragment);
            var options = QO5GeneticDriver.Options.parse(new String[] { "--benchmark", "--indexed", "--statistics-seed", "0",
                    "--buffer-blocks", "8", "--population", "12", "--generations", "2", "--mutation-rate", "1", "--seed", "99" });
            equal(0L, options.statisticsSeed()); equal(8, options.bufferBlocks());
            equal(new GeneticOptimizerConfig(12, 2, 1, 99), options.config());
            check(options.indexed(), "indexed fixture option lost");
        }

        private static void customSql() {
            String text = output("--sql", "SELECT s.name FROM students AS s WHERE s.id = 3", "--population", "8", "--generations", "1");
            check(text.contains("INDEX_SCAN students AS s"), "custom SQL lost alias/index access");
            check(!text.contains("Case: 5 tables"), "custom SQL unexpectedly ran the synthetic fixtures");
        }

        private static void validation() {
            for (String[] options : List.of(new String[] { "--population" }, new String[] { "--unknown", "1" },
                    new String[] { "--buffer-blocks", "2" }, new String[] { "--benchmark", "--sql", "SELECT * FROM students" },
                    new String[] { "--statistics-seed", "1" }, new String[] { "--indexed" }, new String[] { "--mutation-rate", "NaN" })) {
                try { QO5GeneticDriver.Options.parse(options); throw new AssertionError("invalid options accepted"); }
                catch (IllegalArgumentException expected) { }
            }
            try { QO5BenchmarkCatalog.scenario(4, 0, false); throw new AssertionError("invalid fixture size accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    // Benchmark measurement helper

    /** Test/driver comparison of independent search methods; the heuristic here never invokes DP. */
    static final class QO5JoinSearchBenchmark {
        private QO5JoinSearchBenchmark() { }

        // These counters are method-specific: full heuristic plans, DP partial/full candidates, GA chromosomes.
        record Measurement(String method, PlanNode plan, double cost, int candidates, long elapsedNanos) { }
        record Comparison(List<Measurement> measurements, PlanNode selected, double selectedCost) {
            Comparison { measurements = List.copyOf(measurements); }
        }

        static Comparison compare(PlanNode input, IStorageManager storage, int bufferBlocks, GeneticOptimizerConfig config) {
            QO5Plans.requirePlan(input);
            if (storage == null || config == null) throw new IllegalArgumentException("storage and GA config are required");
            List<Measurement> measurements = new ArrayList<>();
            // Separate cost models/cache lifetimes prevent one method warming another method's cache.
            for (String method : List.of("Heuristic (128)", "DP (6/4/20000)", "GA")) {
                CostModel cost = new CostModel(storage, bufferBlocks);
                AccessPathOptimizer access = new AccessPathOptimizer(storage);
                UnaryOperator<PlanNode> prepare = raw -> ProjectionCleanup.normalize(access.chooseAccessPath(raw, cost));
                long start = System.nanoTime();
                measurements.add(cost.withCatalogCache(() -> measure(method, input, cost, prepare, config, start)));
            }
            Measurement best = measurements.get(0);
            for (Measurement measurement : measurements) if (measurement.cost() < best.cost()) best = measurement;
            return new Comparison(measurements, best.plan(), best.cost());
        }

        private static Measurement measure(String method, PlanNode input, CostModel cost, UnaryOperator<PlanNode> prepare,
                                           GeneticOptimizerConfig config, long start) {
            JoinRewriter rewriter = new JoinRewriter();
            PlanNode rewritten = rewriter.mergeSelectionIntoJoin(rewriter.cartesianToJoin(input));
            PlanNode baseline = prepare.apply(input);
            PlanNode searched;
            int evaluated;
            switch (method) {
                case "Heuristic (128)" -> {
                    var candidates = new LinkedHashSet<PlanNode>();
                    for (PlanNode raw : new JoinOrderEnumerator().enumerate(rewritten)) candidates.add(prepare.apply(raw));
                    candidates.add(baseline);
                    searched = new PlanSelector().pickCheapest(List.copyOf(candidates), cost);
                    evaluated = candidates.size();
                }
                case "DP (6/4/20000)" -> {
                    var result = new DynamicProgrammingJoinOptimizer().optimize(rewritten, cost, prepare);
                    searched = result.plan();
                    evaluated = result.evaluatedCandidates();
                }
                case "GA" -> {
                    var result = new GeneticJoinOrderOptimizer(config).optimize(rewritten, cost, prepare);
                    searched = result.plan();
                    evaluated = result.evaluatedIndividuals();
                }
                default -> throw new IllegalArgumentException("unknown search method " + method);
            }
            PlanNode selected = new PlanSelector().pickCheapest(List.of(searched, baseline), cost);
            if (!selected.outputColumns().equals(input.outputColumns())) throw new AssertionError("benchmark changed output schema");
            PlanNodes.validate(selected);
            double estimate = cost.getCost(selected);
            return new Measurement(method, selected, estimate, evaluated, System.nanoTime() - start);
        }
    }

    // Synthetic benchmark catalog

    /** Immutable synthetic catalog, shared by all benchmark methods. It exposes metadata only. */
    static final class QO5BenchmarkCatalog {
        private QO5BenchmarkCatalog() { }

        record Scenario(String sql, IStorageManager storage, Map<String, TableStatistics> statistics,
                        Map<String, List<List<Object>>> sampleRows) { }

        // Fixed illustrative cases, not a claim that GA wins on arbitrary catalogs/seeds.
        static long defaultSeed(int tables) {
            return switch (tables) {
                case 5 -> 3;
                case 6 -> 4;
                default -> throw new IllegalArgumentException("benchmark requires five or six tables");
            };
        }

        static Scenario scenario(int tables, long statisticsSeed, boolean indexed) {
            if (tables != 5 && tables != 6) throw new IllegalArgumentException("benchmark requires five or six tables");
            Random random = new Random(statisticsSeed);
            Map<String, TableStatistics> statistics = new LinkedHashMap<>();
            Map<String, List<List<Object>>> samples = new LinkedHashMap<>();
            for (int i = 0; i < tables; i++) {
                String name = "t" + i;
                long records = (long) Math.pow(10, 2 + random.nextInt(5)) * (1 + random.nextInt(9));
                int blocking = 10 << random.nextInt(5);
                long blocks = (records + blocking - 1) / blocking;
                long idDistinct = Math.max(2, records / (1L << random.nextInt(10)));
                long keyDistinct = Math.max(2, records / (1L << random.nextInt(10)));
                statistics.put(name, new TableStatistics(records, blocks, 24, blocking,
                        Map.of("id", idDistinct, "k", keyDistinct, "v", records)));
                samples.put(name, List.of(List.of(1, 1, 10 + i), List.of(2, 2, 20 + i), List.of(2, 2, 20 + i)));
            }
            Map<String, TableStatistics> fixedStats = java.util.Collections.unmodifiableMap(statistics);
            List<String> names = List.copyOf(statistics.keySet());
            IStorageManager storage = (IStorageManager) Proxy.newProxyInstance(QO5BenchmarkCatalog.class.getClassLoader(),
                    new Class<?>[] { IStorageManager.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "listTables" -> names;
                        case "getSchema" -> {
                            String name = (String) args[0]; requireTable(names, name);
                            yield new TableSchema(name, name, List.of(new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                                    new ColumnSchema("k", DataType.INTEGER, Optional.empty()),
                                    new ColumnSchema("v", DataType.INTEGER, Optional.empty())));
                        }
                        case "getStatistics" -> { requireTable(names, (String) args[0]); yield fixedStats.get((String) args[0]); }
                        case "getIndexes" -> {
                            String name = (String) args[0]; requireTable(names, name);
                            yield indexed && name.equals("t2") ? List.of(new IndexMetadata("t2_id", name, "id", IndexType.HASH))
                                    : List.<IndexMetadata>of();
                        }
                        case "getIndexStatistics" -> {
                            if (!indexed || !"t2_id".equals(args[0])) throw new AssertionError("unknown benchmark index");
                            yield MockStorageManager.defaultIndexStatistics(new IndexMetadata("t2_id", "t2", "id", IndexType.HASH));
                        }
                        default -> throw new AssertionError("benchmark must not perform record I/O: " + method.getName());
                    });
            StringBuilder sql = new StringBuilder("SELECT t0.v, t" + (tables - 1) + ".v FROM t0");
            for (int i = 1; i < tables; i++) sql.append(" JOIN t").append(i).append(" ON t").append(i - 1)
                    .append(".k = t").append(i).append(".id");
            if (indexed) sql.append(" WHERE t2.id = 2");
            return new Scenario(sql.toString(), storage, fixedStats, Map.copyOf(samples));
        }

        private static void requireTable(List<String> names, String name) {
            if (!names.contains(name)) throw new AssertionError("nonphysical benchmark table " + name);
        }
    }
}
