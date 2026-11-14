package sqlike.qo;

import java.util.*;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

/** Compact acceptance checks for QO-09 selection and QO-10 projection rewriting. */
public final class QO4Test {
    private static int passed, failed;
    private QO4Test() { }

    public static int run() {
        passed = failed = 0;
        test("splits AND and pushes table-local filters", QO4Test::selectionPushdown);
        test("orders simple filters by estimated selectivity", QO4Test::selectivityOrder);
        test("keeps cross-input predicates above joins", QO4Test::crossPredicateStays);
        test("swaps adjacent filters and preserves results", QO4Test::filterSwap);
        test("moves filters through sort and safe projection", QO4Test::filterMovement);
        test("keeps columns needed by SELECT, WHERE, ORDER BY and join", QO4Test::projectionRequirements);
        test("pushes projection to the useful join side", QO4Test::oneSidedProjection);
        test("flattens projection chains and preserves output order", QO4Test::projectionShape);
        test("removes identity projections", QO4Test::identityProjection);
        test("maintains NATURAL JOIN metadata and unique output names", QO4Test::naturalJoinMetadata);
        test("leaves projection above an ambiguously mapped NATURAL JOIN", QO4Test::ambiguousNaturalProjection);
        test("handles aliased relations during pushdown", QO4Test::aliasPushdown);
        test("keeps LIMIT and correlated subqueries as rewrite boundaries", QO4Test::rewriteBoundaries);
        test("keeps parsed subquery predicates at their original scope", QO4Test::parsedSubqueryBoundary);
        test("tracks outer columns read by correlated subqueries", QO4Test::correlatedSubqueryDependencies);
        test("pipeline leaves no PROJECT over PROJECT or identity PROJECT and keeps results", QO4Test::projectionShapeAcrossPipeline);
        test("preserves result rows across representative rewrite", QO4Test::rewriteIsEquivalent);
        test("does not mutate the input plan and preserves metadata", QO4Test::inputAndMetadata);
        System.out.printf("QO4: %d passed, %d failed%n", passed, failed);
        return failed;
    }

    private static void selectionPushdown() {
        PlanNode root = rewrite("SELECT students.name FROM students, dept WHERE students.id > 10 AND dept.dept_id > 2");
        PlanNode cart = find(root, PlanNodeKind.CARTESIAN);
        check(cart != null, "expected Cartesian product");
        check(hasFilter(cart.children().get(0), "(students.id > 10)"), "left filter not pushed");
        check(hasFilter(cart.children().get(1), "(dept.dept_id > 2)"), "right filter not pushed");
        List<PlanNode> filters = ofKind(root, PlanNodeKind.FILTER);
        eq(2, filters.size());
        for (PlanNode f : filters) check(f.predicate().orElseThrow().operator().orElse(null) != ExpressionOperator.AND,
                "AND was not decomposed");
    }

    private static void selectivityOrder() {
        PlanNode root = rewrite("SELECT name FROM students WHERE gpa >= 3.0 AND id = 5");
        PlanNode first = find(root, PlanNodeKind.FILTER);
        eq("(students.gpa >= 3.0)", PlanPrinter.expr(first.predicate().orElseThrow()));
        eq("(students.id = 5)", PlanPrinter.expr(first.children().get(0).predicate().orElseThrow()));
    }

    private static void crossPredicateStays() {
        PlanNode root = rewrite("SELECT students.name FROM students JOIN dept ON students.dept_id = dept.dept_id "
                + "WHERE students.id = dept.dept_id");
        PlanNode f = root.children().get(0);
        eq(PlanNodeKind.FILTER, f.kind());
        eq("(students.id = dept.dept_id)", PlanPrinter.expr(f.predicate().orElseThrow()));
        check(find(f.children().get(0), PlanNodeKind.JOIN) != null, "cross predicate was pushed into a join input");
    }

    private static void filterSwap() {
        PlanNode scan = PlanNodes.scan("students");
        PlanNode input = PlanNodes.filter(PlanNodes.filter(scan, expr("id < 3")), expr("id > 1"));
        PlanNode output = new SelectionProjectionRewriter().rewrite(input);
        eq("(id < 3)", PlanPrinter.expr(output.predicate().orElseThrow()));
        eq("(id > 1)", PlanPrinter.expr(output.children().get(0).predicate().orElseThrow()));
        eq(eval(input, rows()), eval(output, rows()));
    }

    private static void filterMovement() {
        PlanNode sorted = PlanNodes.sort(PlanNodes.scan("students"),
                new SortSpec(Refs.column(null, "id"), SortDirection.DESC));
        PlanNode moved = new SelectionProjectionRewriter().rewrite(PlanNodes.filter(sorted, expr("id > 1")));
        eq(PlanNodeKind.SORT, moved.kind());
        eq(PlanNodeKind.FILTER, moved.children().get(0).kind());
        PlanNode project = PlanNodes.project(PlanNodes.scan("students"), List.of(Refs.column(null, "name")));
        PlanNode guarded = new SelectionProjectionRewriter().rewrite(PlanNodes.filter(project, expr("id > 1")));
        eq(PlanNodeKind.FILTER, guarded.kind()); // id is absent from the projection
    }

    private static void projectionRequirements() {
        PlanNode root = rewrite("SELECT students.name FROM students WHERE id = 5 AND gpa > 3 ORDER BY dept_id");
        PlanNode scanProject = findProjectAboveScan(root, "students");
        check(scanProject != null, "expected pushed projection");
        Set<String> cols = new HashSet<>(scanProject.projection().stream().map(PlanPrinter::col).toList());
        check(cols.containsAll(Set.of("students.name", "students.id", "students.gpa", "students.dept_id")),
                "dropped required column(s): " + cols);
        check(!cols.contains("students.dept_id_unused"), "unexpected column");

        PlanNode join = rewrite("SELECT dept.dname, students.name FROM students JOIN dept "
                + "ON students.dept_id = dept.dept_id").children().get(0);
        check(join.children().get(0).projection().stream().map(PlanPrinter::col).toList()
                .containsAll(List.of("students.name", "students.dept_id")), "left join key/selection column missing");
        check(join.children().get(1).projection().stream().map(PlanPrinter::col).toList()
                .containsAll(List.of("dept.dname", "dept.dept_id")), "right join key/selection column missing");
    }

    private static void oneSidedProjection() {
        PlanNode cart = find(rewrite("SELECT students.name FROM students, dept"), PlanNodeKind.CARTESIAN);
        check(cart != null, "expected Cartesian product");
        eq(PlanNodeKind.PROJECT, cart.children().get(0).kind());
        eq(PlanNodeKind.SCAN, cart.children().get(1).kind()); // empty projection would invalidate the plan
        PlanNodes.validate(cart);
    }

    private static void projectionShape() {
        PlanNode scan = PlanNodes.scan("students");
        PlanNode inner = PlanNodes.project(scan, List.of(Refs.column("students", "id"), Refs.column("students", "name")));
        PlanNode root = new SelectionProjectionRewriter().rewrite(
                PlanNodes.project(inner, List.of(Refs.column("students", "name"))));
        eq(PlanNodeKind.SCAN, root.children().get(0).kind());
        eq(List.of("students.name"), root.projection().stream().map(PlanPrinter::col).toList());
        PlanNode query = rewrite("SELECT dept.dname, students.name FROM students JOIN dept "
                + "ON students.dept_id = dept.dept_id");
        eq(List.of("dept.dname", "students.name"), query.projection().stream().map(PlanPrinter::col).toList());
        eq(List.of("dname", "name"), query.outputColumns().stream().map(ResultColumn::name).toList());
    }

    private static void identityProjection() {
        PlanNode root = rewrite("SELECT * FROM students WHERE id = 5");
        eq(0, ofKind(root, PlanNodeKind.PROJECT).size());
        eq(PlanNodeKind.FILTER, root.kind());
        eq(PlanNodeKind.SCAN, root.children().get(0).kind());
    }

    private static void naturalJoinMetadata() {
        IStorageManager storage = MockStorageManager.create();
        ParsedQuery query = new QueryOptimizer(storage).parseQuery(
                "SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes");
        PlanNode plan = new SelectionProjectionRewriter().rewrite(new Binder(storage).bind(query));
        for (PlanNode join : ofKind(plan, PlanNodeKind.JOIN)) {
            if (join.join().orElseThrow().joinType() != JoinType.NATURAL) continue;
            Set<String> names = new HashSet<>();
            for (ResultColumn col : join.outputColumns())
                check(names.add(col.name().toLowerCase()), "duplicate NATURAL JOIN column: " + col.name());
        }
        check(new CardinalityEstimator(storage).estimateRows(plan.children().get(0)) > 0,
                "estimator cannot read rewritten NATURAL JOIN metadata");
        PlanNode narrow = rewrite("SELECT students.name FROM students NATURAL JOIN dept NATURAL JOIN takes");
        check(narrow.outputColumns().size() == 1, "narrow projection was lost on a multi-table NATURAL JOIN");
    }

    private static void ambiguousNaturalProjection() {
        PlanNode students = scanWithColumns("students",
                new ResultColumn("id", Optional.of(Refs.column("students", "id")), DataType.INTEGER),
                new ResultColumn("name", Optional.of(Refs.column("students", "name")), DataType.VARCHAR));
        PlanNode dept = scanWithColumns("dept",
                new ResultColumn("id", Optional.of(Refs.column("dept", "id")), DataType.INTEGER));
        PlanNode left = withOutputs(PlanNodes.join(students, dept,
                new JoinSpec(JoinType.THETA, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of())),
                List.of(students.outputColumns().get(0), students.outputColumns().get(1), dept.outputColumns().get(0)));
        PlanNode takes = scanWithColumns("takes",
                new ResultColumn("id", Optional.of(Refs.column("takes", "id")), DataType.INTEGER));
        PlanNode natural = withOutputs(PlanNodes.join(left, takes,
                new JoinSpec(JoinType.NATURAL, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of("id"))),
                left.outputColumns());
        PlanNode input = PlanNodes.project(natural, List.of(Refs.column("students", "name")));
        PlanNode output = new SelectionProjectionRewriter().rewrite(input);
        eq(PlanNodeKind.PROJECT, output.kind());
        eq(List.of("students.name"), output.projection().stream().map(PlanPrinter::col).toList());
    }

    private static void aliasPushdown() {
        PlanNode root = rewrite("SELECT s.name FROM students AS s, dept AS d "
                + "WHERE s.id > 1 AND s.dept_id = d.dept_id");
        PlanNode cart = find(root, PlanNodeKind.CARTESIAN);
        check(cart != null, "Cartesian product disappeared");
        check(hasFilter(cart.children().get(0), "(s.id > 1)"), "alias filter was not pushed to its input");
        check(!hasFilter(cart.children().get(1), "(s.id > 1)"), "alias filter reached the wrong input");
        check(findProjectAboveScan(root, "students") != null, "projection was not pushed to aliased scan");
    }

    private static void rewriteBoundaries() {
        IStorageManager storage = MockStorageManager.create();
        PlanNode bound = new Binder(storage).bind(new QueryOptimizer(storage).parseQuery("SELECT * FROM students"));
        PlanNode scan = bound.children().get(0);
        PlanNode limit = PlanNodes.limit(scan, new LimitSpec(1));
        PlanNode filtered = new SelectionProjectionRewriter().rewrite(
                PlanNodes.filter(limit, expr("students.id > 1")));
        eq(PlanNodeKind.FILTER, filtered.kind());
        eq(PlanNodeKind.LIMIT, filtered.children().get(0).kind());
        PlanNode projected = new SelectionProjectionRewriter().rewrite(
                PlanNodes.project(limit, List.of(Refs.column("students", "name"))));
        eq(PlanNodeKind.PROJECT, projected.kind());
        eq(PlanNodeKind.LIMIT, projected.children().get(0).kind());

        ParsedQuery parsed = new QueryOptimizer(storage).parseQuery("SELECT students.name, dept.dname FROM students "
                + "JOIN dept ON students.dept_id = dept.dept_id");
        PlanNode boundJoin = new Binder(storage).bind(parsed).children().get(0);
        PlanNode subqueryRoot = PlanNodes.filter(PlanNodes.scan("takes"), expr("takes.id = students.id"));
        Expression exists = new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), Optional.of(new SubquerySpec(SubqueryType.EXISTS, subqueryRoot, true)));
        PlanNode filteredJoin = PlanNodes.filter(boundJoin, exists);
        PlanNode correlated = new SelectionProjectionRewriter().rewrite(
                PlanNodes.project(filteredJoin, List.of(Refs.column("students", "name"))));
        eq(PlanNodeKind.PROJECT, correlated.kind());
        eq(PlanNodeKind.FILTER, correlated.children().get(0).kind());
        eq(PlanNodeKind.JOIN, correlated.children().get(0).children().get(0).kind());
    }

    private static void parsedSubqueryBoundary() {
        IStorageManager storage = MockStorageManager.create();
        ParsedQuery parsed = new QueryOptimizer(storage).parseQuery(
                "SELECT name FROM students WHERE EXISTS (SELECT dept_id FROM dept)");
        PlanNode bound = new Binder(storage).bind(parsed);
        PlanNode rewritten = new SelectionProjectionRewriter(new CardinalityEstimator(storage)).rewrite(bound);
        PlanNode filter = find(rewritten, PlanNodeKind.FILTER);
        check(filter != null, "subquery filter disappeared");
        eq(ExpressionKind.SUBQUERY, filter.predicate().orElseThrow().kind());
        eq(SubqueryType.EXISTS, filter.predicate().orElseThrow().subquery().orElseThrow().type());
        eq(PlanNodeKind.SCAN, filter.children().get(0).kind());
        PlanNodes.validate(rewritten);
    }

    private static void rewriteIsEquivalent() {
        IStorageManager storage = MockStorageManager.create();
        ParsedQuery q = new QueryOptimizer(storage).parseQuery("SELECT students.name, dept.dname FROM students, dept "
                + "WHERE students.gpa >= 3.0 AND students.dept_id = dept.dept_id ORDER BY students.name");
        PlanNode before = new Binder(storage).bind(q);
        PlanNode after = new SelectionProjectionRewriter().rewrite(before);
        eq(eval(before, rows()), eval(after, rows()));
    }

    private static void inputAndMetadata() {
        Expression predicate = expr("id = 1 AND name = 'Ada'");
        PlanNode input = PlanNodes.filter(PlanNodes.scan("students"), predicate);
        PlanNode out = new SelectionProjectionRewriter().rewrite(input);
        eq(predicate, input.predicate().orElseThrow());
        eq(PlanNodeKind.SCAN, input.children().get(0).kind());
        for (PlanNode n : collect(out)) {
            if (n.kind() == PlanNodeKind.FILTER || n.kind() == PlanNodeKind.SORT)
                eq(n.children().get(0).outputColumns(), n.outputColumns());
            if (n.kind() == PlanNodeKind.PROJECT) eq(n.projection().size(), n.outputColumns().size());
        }
        PlanNode meta = rewrite("SELECT gpa, name FROM students");
        eq(DataType.FLOAT, meta.outputColumns().get(0).dataType());
        eq(Optional.of(Refs.column("students", "gpa")), meta.outputColumns().get(0).source());
    }

    private static PlanNode rewrite(String sql) {
        IStorageManager s = MockStorageManager.create();
        return new SelectionProjectionRewriter(new CardinalityEstimator(s))
                .rewrite(new Binder(s).bind(new QueryOptimizer(s).parseQuery(sql)));
    }
    private static PlanNode scanWithColumns(String table, ResultColumn... columns) {
        return withOutputs(PlanNodes.scan(table), List.of(columns));
    }
    private static PlanNode withOutputs(PlanNode node, List<ResultColumn> outputs) {
        return new PlanNode(node.kind(), node.children(), outputs, node.estimatedRows(), node.scan(), node.predicate(),
                node.projection(), node.join(), node.sort(), node.mutation(), node.createIndex(), node.limit(),
                node.createTable(), node.dropTable());
    }
    private static Expression expr(String sql) {
        TokenStream ts = new Lexer().tokenize(sql);
        Expression e = new ExpressionParser().parseExpression(ts); ts.expectEndOfStatement(); return e;
    }
    private static PlanNode find(PlanNode n, PlanNodeKind kind) {
        if (n.kind() == kind) return n;
        for (PlanNode c : n.children()) { PlanNode found = find(c, kind); if (found != null) return found; }
        return null;
    }
    private static PlanNode findProjectAboveScan(PlanNode n, String table) {
        if (n.kind() == PlanNodeKind.PROJECT && n.children().get(0).kind() == PlanNodeKind.SCAN
                && n.children().get(0).scan().orElseThrow().tableName().equalsIgnoreCase(table)) return n;
        for (PlanNode c : n.children()) { PlanNode found = findProjectAboveScan(c, table); if (found != null) return found; }
        return null;
    }
    private static boolean hasFilter(PlanNode n, String text) {
        return (n.kind() == PlanNodeKind.FILTER && PlanPrinter.expr(n.predicate().orElseThrow()).equals(text))
                || n.children().stream().anyMatch(c -> hasFilter(c, text));
    }
    private static List<PlanNode> ofKind(PlanNode n, PlanNodeKind kind) {
        List<PlanNode> out = new ArrayList<>(); if (n.kind() == kind) out.add(n);
        n.children().forEach(c -> out.addAll(ofKind(c, kind))); return out;
    }
    private static List<PlanNode> collect(PlanNode n) {
        List<PlanNode> out = new ArrayList<>(); out.add(n); n.children().forEach(c -> out.addAll(collect(c))); return out;
    }
    private static Map<String,List<Map<ColumnRef,Object>>> rows() {
        Map<String,List<Map<ColumnRef,Object>>> t = new HashMap<>();
        t.put("students", List.of(row("students", "id",1,"name","Ada","gpa",3.8,"dept_id",10),
                row("students", "id",2,"name","Bima","gpa",2.9,"dept_id",20),
                row("students", "id",3,"name","Cici","gpa",3.4,"dept_id",10)));
        t.put("dept", List.of(row("dept", "dept_id",10,"dname","Math"), row("dept", "dept_id",20,"dname","CS")));
        return t;
    }
    private static Map<ColumnRef,Object> row(String table, Object... fields) {
        Map<ColumnRef,Object> r = new HashMap<>();
        for (int i=0;i<fields.length;i+=2) r.put(Refs.column(table,(String)fields[i]),fields[i+1]); return r;
    }
    private static List<Map<ColumnRef,Object>> eval(PlanNode n, Map<String,List<Map<ColumnRef,Object>>> tables) {
        return switch (n.kind()) {
            case SCAN -> tables.get(n.scan().orElseThrow().tableName().toLowerCase());
            case FILTER -> eval(n.children().get(0),tables).stream()
                    .filter(r -> Boolean.TRUE.equals(value(n.predicate().orElseThrow(),r))).toList();
            case PROJECT -> eval(n.children().get(0),tables).stream().map(r -> {
                Map<ColumnRef,Object> p=new HashMap<>(); n.projection().forEach(c -> p.put(c,col(r,c))); return p;
            }).toList();
            case SORT -> { List<Map<ColumnRef,Object>> x=new ArrayList<>(eval(n.children().get(0),tables));
                SortSpec s=n.sort().orElseThrow(); x.sort((a,b)->cmp(col(a,s.column()),col(b,s.column())));
                if(s.direction()==SortDirection.DESC) Collections.reverse(x); yield x; }
            case CARTESIAN, JOIN -> join(n,tables);
            default -> throw new AssertionError("unsupported test node: "+n.kind());
        };
    }
    private static List<Map<ColumnRef,Object>> join(PlanNode n, Map<String,List<Map<ColumnRef,Object>>> t) {
        List<Map<ColumnRef,Object>> out=new ArrayList<>();
        for(var a:eval(n.children().get(0),t)) for(var b:eval(n.children().get(1),t)) {
            Map<ColumnRef,Object> r=new HashMap<>(a); r.putAll(b);
            if(n.kind()==PlanNodeKind.CARTESIAN || Boolean.TRUE.equals(value(n.join().orElseThrow().condition().orElseThrow(),r))) out.add(r);
        } return out;
    }
    private static Object value(Expression e, Map<ColumnRef,Object> r) {
        if(e.kind()==ExpressionKind.COLUMN) return col(r,e.column().orElseThrow());
        if(e.kind()==ExpressionKind.LITERAL) { var l=e.literal().orElseThrow(); return switch(l.type()) {
            case INTEGER -> l.integerValue().orElseThrow(); case FLOAT -> l.floatValue().orElseThrow();
            case CHAR, VARCHAR -> l.textValue().orElseThrow(); }; }
        Object a=value(e.operands().get(0),r), b=value(e.operands().get(1),r);
        if(e.operator().orElseThrow()==ExpressionOperator.AND) return (Boolean)a && (Boolean)b;
        int c=cmp(a,b); return switch(e.operator().orElseThrow()) {
            case EQ -> c==0; case NE -> c!=0; case GT -> c>0; case GE -> c>=0; case LT -> c<0; case LE -> c<=0;
            default -> throw new AssertionError("unsupported test operator"); };
    }
    private static Object col(Map<ColumnRef,Object> r, ColumnRef c) {
        if(r.containsKey(c)) return r.get(c);
        List<Object> matches=r.entrySet().stream().filter(e -> e.getKey().columnName().equalsIgnoreCase(c.columnName())
                && (c.tableName().isEmpty() || e.getKey().tableName().equals(c.tableName()))).map(Map.Entry::getValue).toList();
        if(matches.size()!=1) throw new AssertionError("cannot resolve "+PlanPrinter.col(c)); return matches.get(0);
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static int cmp(Object a,Object b) {
        if(a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(),y.doubleValue());
        return ((Comparable)a).compareTo(b);
    }
    private static void correlatedSubqueryDependencies() {
        String sql = "SELECT s.name FROM students AS s, dept AS d WHERE s.dept_id = d.dept_id AND EXISTS "
                + "(SELECT * FROM takes AS t WHERE t.id = s.id AND t.course_id = d.dept_id)";
        PlanNode root = rewrite(sql);
        PlanNodes.validate(root);
        eq(PlanNodeKind.PROJECT, root.kind());
        PlanNode filter = root.children().get(0);
        eq(PlanNodeKind.FILTER, filter.kind());
        Expression whole = filter.predicate().orElseThrow();
        Expression predicate = subqueryOperand(whole);
        check(predicate != null, "the subquery conjunct must stay in the filter above the join");
        check(predicate.subquery().orElseThrow().correlated(), "subquery must be flagged correlated");
        // The correlated predicate reads s.id and d.dept_id, so it must stay above the join that provides both.
        PlanNode join = filter.children().get(0);
        check(join.kind() == PlanNodeKind.JOIN || join.kind() == PlanNodeKind.CARTESIAN, "predicate must sit directly above the join");
        check(findScan(join, "s") && findScan(join, "d"), "join must provide both referenced relations");
        IStorageManager s = MockStorageManager.create();
        SelectionProjectionRewriter rewriter = new SelectionProjectionRewriter(new CardinalityEstimator(s));
        eq(Set.of(Refs.column("s", "id"), Refs.column("d", "dept_id")), new HashSet<>(rewriter.columns(predicate)));
        // A nested subquery reaching the outermost query contributes that column to the middle subquery as well.
        PlanNode nested = new Binder(s).bind(new QueryOptimizer(s).parseQuery("SELECT s.name FROM students AS s WHERE EXISTS "
                + "(SELECT * FROM takes AS t WHERE t.id = 1 AND EXISTS (SELECT * FROM dept AS d WHERE d.dept_id = s.dept_id AND d.dname = t.grade))"));
        Expression middle = subqueryOperand(find(nested, PlanNodeKind.FILTER).predicate().orElseThrow());
        eq(Set.of(Refs.column("s", "dept_id")), new HashSet<>(rewriter.columns(middle)));
        PlanNodes.validate(new SelectionProjectionRewriter(new CardinalityEstimator(s)).rewrite(nested));
    }
    private static Expression subqueryOperand(Expression e) {
        if (e.kind() == ExpressionKind.SUBQUERY) return e;
        for (Expression operand : e.operands()) {
            Expression found = subqueryOperand(operand);
            if (found != null) return found;
        }
        return null;
    }
    private static boolean findScan(PlanNode node, String qualifier) {
        if (node.scan().map(scan -> scan.alias().orElse(scan.tableName()).equalsIgnoreCase(qualifier)).orElse(false)) return true;
        return node.children().stream().anyMatch(child -> findScan(child, qualifier));
    }
    private static final List<String> SHAPE_QUERIES = List.of(
            "SELECT * FROM students", "SELECT name FROM students", "SELECT name, gpa FROM students WHERE gpa > 3.0 ORDER BY gpa DESC",
            "SELECT id FROM students ORDER BY id", "SELECT * FROM students WHERE id = 3 AND gpa > 3.0",
            "SELECT s.name, d.dname FROM students AS s JOIN dept AS d ON s.dept_id = d.dept_id",
            "SELECT s.name, d.dname FROM students AS s JOIN dept AS d ON s.dept_id = d.dept_id WHERE s.gpa > 3.0 ORDER BY s.name",
            "SELECT * FROM students, dept", "SELECT * FROM students, dept WHERE students.dept_id = dept.dept_id",
            "SELECT students.name FROM students, dept WHERE students.dept_id = dept.dept_id",
            "SELECT a.id, b.id FROM students AS a, students AS b WHERE a.dept_id = b.dept_id AND a.id = 1",
            "SELECT a.id, b.id FROM students AS a JOIN students AS b ON a.dept_id = b.dept_id WHERE a.id = 1",
            "SELECT * FROM students NATURAL JOIN dept", "SELECT * FROM students NATURAL JOIN dept NATURAL JOIN takes",
            "SELECT id FROM students NATURAL JOIN takes ORDER BY id", "SELECT dname FROM students NATURAL JOIN dept WHERE dept_id = 10",
            "SELECT * FROM students, dept NATURAL JOIN takes",
            "SELECT students.name, dept.dname, takes.course_id FROM students JOIN takes ON students.id = takes.id "
                    + "JOIN dept ON students.dept_id = dept.dept_id WHERE takes.course_id = 100",
            "SELECT a.name FROM students AS a, students AS b, dept AS d, takes AS t WHERE a.dept_id = d.dept_id AND b.id = t.id AND a.id = b.id",
            "SELECT name FROM students LIMIT 3", "SELECT * FROM students LIMIT 1", "SELECT name FROM students WHERE id > 1 ORDER BY name LIMIT 2",
            "SELECT s.name, d.dname FROM students AS s JOIN dept AS d ON s.dept_id = d.dept_id ORDER BY s.name LIMIT 2",
            "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id)",
            "SELECT name FROM students WHERE id IN (SELECT id FROM takes)",
            "SELECT name FROM students WHERE gpa > (SELECT course_id FROM takes WHERE grade = 'A')",
            "SELECT s.name FROM students AS s, dept AS d WHERE s.dept_id = d.dept_id AND EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id)",
            "SELECT s.name FROM students AS s WHERE EXISTS (SELECT * FROM takes AS t WHERE t.id = s.id AND "
                    + "EXISTS (SELECT * FROM dept AS d WHERE d.dept_id = s.dept_id))");

    /** No PROJECT may sit directly on another PROJECT or equal its input, in the main plan or in any subquery plan. */
    private static void projectionShapeAcrossPipeline() {
        IStorageManager storage = QO5TestSupport.catalog();
        QueryOptimizer optimizer = new QueryOptimizer(storage);
        check(SHAPE_QUERIES.size() >= 25, "need at least 25 queries");
        for (String sql : SHAPE_QUERIES) {
            PlanNode after = optimizer.optimizeQuery(optimizer.parseQuery(sql)).root();
            PlanNodes.validate(after);
            requireNoRedundantProject(after, sql);
            if (sql.contains("(SELECT")) continue; // the test evaluator has no subquery support
            PlanNode before = new Binder(storage).bind(optimizer.parseQuery(sql));
            QO5TestSupport.equivalent(before, after, QO5TestSupport.data(), storage);
            eq(before.outputColumns().stream().map(ResultColumn::name).toList(),
                    after.outputColumns().stream().map(ResultColumn::name).toList());
            if (sql.contains("ORDER BY id"))
                eq(QO5TestSupport.eval(before, QO5TestSupport.data(), storage).values(),
                        QO5TestSupport.eval(after, QO5TestSupport.data(), storage).values());
        }
    }

    private static void requireNoRedundantProject(PlanNode node, String sql) {
        if (node.kind() == PlanNodeKind.PROJECT) {
            check(node.children().get(0).kind() != PlanNodeKind.PROJECT, "PROJECT over PROJECT: " + sql);
            check(!QO5Plans.isIdentityProject(node), "identity PROJECT: " + sql);
        }
        node.children().forEach(child -> requireNoRedundantProject(child, sql));
        List<Expression> expressions = new ArrayList<>();
        node.predicate().ifPresent(expressions::add);
        node.join().flatMap(JoinSpec::condition).ifPresent(expressions::add);
        for (Expression expression : expressions) requireNoRedundantProject(expression, sql);
    }

    private static void requireNoRedundantProject(Expression expression, String sql) {
        expression.subquery().ifPresent(spec -> requireNoRedundantProject(spec.root(), sql));
        expression.operands().forEach(operand -> requireNoRedundantProject(operand, sql));
    }
    private static void test(String name, Runnable body) {
        try { body.run(); passed++; System.out.println("PASS "+name); }
        catch(Throwable e) { failed++; System.out.println("FAIL "+name+": "+e.getMessage()); }
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static void eq(Object expected,Object actual) {
        if(!Objects.equals(expected,actual)) throw new AssertionError("expected="+expected+", actual="+actual);
    }
}
