package sqlike.qo;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiConsumer;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;
import static sqlike.qo.QO5TestSupport.*;

/** QO-18 and alias/LIMIT regressions, registered in the existing QO5 suite. */
final class QO5GeneticTest {
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
        test.accept("LIMIT zero and positive preserve their nodes and skip GA search", QO5GeneticTest::limits);
        test.accept("subquery plans remain search boundaries", QO5GeneticTest::subqueries);
        test.accept("GA leaves input plans and returned history immutable", QO5GeneticTest::immutability);
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
            check(report.plan() == before, "search crossed LIMIT");
            equal(0, report.evaluatedIndividuals());
            equal(eval(before, data(), storage), eval(report.plan(), data(), storage));
        }
    }

    private static void subqueries() {
        PlanNode child = rewritten(FOUR);
        Expression sub = new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.of(new SubquerySpec(SubqueryType.EXISTS, bound("SELECT * FROM dept"), false)));
        PlanNode before = QO5Plans.filter(child, sub);
        var result = new GeneticJoinOrderOptimizer(config()).optimize(before, new CostModel(catalog()));
        equal(0, result.evaluatedIndividuals());
        check(result.plan() == before, "GA entered subquery scope");
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
                    case "getStatistics" -> base.getStatistics((String) args[0]);
                    case "getIndexes" -> {
                        String table = (String) args[0];
                        check(base.listTables().contains(table), "alias leaked into physical catalog access");
                        yield table.equals("students") ? List.of(new IndexMetadata("students_id", table, "id", IndexType.HASH))
                                : List.<IndexMetadata>of();
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
