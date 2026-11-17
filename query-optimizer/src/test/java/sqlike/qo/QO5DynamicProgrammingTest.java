package sqlike.qo;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;
import static sqlike.qo.QO5TestSupport.*;

/** DP is checked against exhaustive labeled binary trees, not the bounded heuristic enumerator. */
final class QO5DynamicProgrammingTest {
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
