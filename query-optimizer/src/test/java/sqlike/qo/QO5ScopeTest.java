package sqlike.qo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;
import static sqlike.qo.QO5TestSupport.*;

/** Audit regressions exercise actual bound scopes with an independent test-only evaluator. */
final class QO5ScopeTest {
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
