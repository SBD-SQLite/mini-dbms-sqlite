package sqlike.qo;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;
import static sqlike.qo.QO5TestSupport.*;

/** QO-13/QO-14 checks use metadata-only SM and an independent in-memory INDEX_SCAN evaluator. */
final class QO5AccessPathTest {
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
        test.accept("LIMIT and correlated subqueries block access transformations", QO5AccessPathTest::boundaries);
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
        PlanNode limit = PlanNodes.limit(child, new LimitSpec(1));
        check(fixture.choose(limit) == limit, "rewrote below LIMIT");
        Expression subquery = new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Optional.of(new SubquerySpec(SubqueryType.EXISTS, bound("SELECT * FROM dept"), true)));
        PlanNode filter = QO5Plans.filter(child, subquery);
        equal(List.of(filter), fixture.paths(filter));
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
