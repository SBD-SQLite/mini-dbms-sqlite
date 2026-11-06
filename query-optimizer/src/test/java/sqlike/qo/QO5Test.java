package sqlike.qo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlike.shared.*;
import static sqlike.qo.QO5TestSupport.*;

/** QO-11/QO-12 acceptance and regression checks; invoked by the existing QODriver. */
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
        test("LIMIT is a rewrite and enumeration boundary", QO5Test::limitBoundary);
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
        PlanNode before = rewritten(TWO);
        PlanNode limit = PlanNodes.limit(before, new LimitSpec(2));
        limit = new PlanNode(limit.kind(), limit.children(), before.outputColumns(), Optional.empty(),
                limit.scan(), limit.predicate(), limit.projection(), limit.join(), limit.sort(), limit.mutation(),
                limit.createIndex(), limit.limit(), limit.createTable(), limit.dropTable());
        check(new JoinRewriter().cartesianToJoin(limit) == limit, "rewrote below LIMIT");
        equal(List.of(limit), candidates(limit));
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
        equal(List.of(filter), candidates(filter));
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
}
