package sqlike.qo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sqlike.shared.*;

/** QO1 acceptance tests: Lexer, SELECT parser, facade (QueryOptimizer) dan PlanPrinter, sama  QO-16 scenario */
public final class QO1Test {
    static final Path DEFAULT_SCENARIOS = Path.of("query-optimizer/src/test/resources/scenarios.txt");
    private static int passed;
    private static int failed;
    private static final Lexer LEXER = new Lexer();
    private static final QueryOptimizer QO = new QueryOptimizer(MockStorageManager.create());

    public static void main(String[] args) {
        if (run() != 0) System.exit(1);
    }

    public static int run() {
        passed = 0;
        failed = 0;
        test("keywords are case-insensitive", () -> eq(QO.parseQuery("select * from students").root(), QO.parseQuery("SeLeCt * FrOm students").root()));
        test("whitespace and newlines are insignificant", () ->
                eq(QO.parseQuery("SELECT id, name FROM students WHERE id = 1 ORDER BY name DESC"),
                        QO.parseQuery("SELECT id,\n\tname\nFROM\tstudents\nWHERE id = 1\n\tORDER BY name\n\tDESC")));
        test("tokens carry source offsets", () -> {
            TokenStream ts = LEXER.tokenize("SELECT a FROM t");
            ts.next();
            eq(7, ts.next().offset());
        });
        test("operators tokenize", () -> {
            TokenStream ts = LEXER.tokenize("<> <= >= = < > + - * / , ( ) ;");
            int n = 0;
            while (!ts.isAtEnd()) { ts.next(); n++; }
            eq(14, n);
        });
        test("SELECT * shape", () -> {
            PlanNode root = QO.parseQuery("SELECT * FROM students").root().orElseThrow();
            eq(PlanNodeKind.PROJECT, root.kind());
            eq(PlanNodeKind.SCAN, root.children().get(0).kind());
        });
        test("WHERE and ORDER BY shape", () -> {
            PlanNode root = QO.parseQuery("SELECT name FROM students WHERE id = 5 ORDER BY name DESC").root().orElseThrow();
            eq(PlanNodeKind.PROJECT, root.kind());
            eq(PlanNodeKind.SORT, root.children().get(0).kind());
            eq(PlanNodeKind.FILTER, root.children().get(0).children().get(0).kind());
        });
        test("NATURAL JOIN of three tables is left-deep", () -> {
            PlanNode join = QO.parseQuery("SELECT * FROM a NATURAL JOIN b NATURAL JOIN c").root().orElseThrow().children().get(0);
            eq(JoinType.NATURAL, join.join().orElseThrow().joinType());
            eq(PlanNodeKind.JOIN, join.children().get(0).kind());
            eq(PlanNodeKind.SCAN, join.children().get(1).kind());
        });
        test("comma list becomes CARTESIAN", () ->
                eq(PlanNodeKind.CARTESIAN, QO.parseQuery("SELECT a FROM t, u").root().orElseThrow().children().get(0).kind()));
        test("parsed plans validate", () -> PlanNodes.validate(QO.parseQuery("SELECT a.x FROM a JOIN b ON a.id = b.id WHERE a.x > 1").root().orElseThrow()));
        test("PlanPrinter prints an indented tree", () -> {
            String text = new PlanPrinter().print(QO.parseQuery("SELECT name FROM students WHERE id = 5").root().orElseThrow());
            eq("PROJECT [name]\n  FILTER (id = 5)\n    SCAN students [full scan]\n", text);
        });
        test("PlanPrinter shows alias, join algorithm and cartesian algorithm", () -> {
            PlanNode scan = PlanNodes.scan("students");
            PlanNode aliased = new PlanNode(scan.kind(), scan.children(), scan.outputColumns(), scan.estimatedRows(),
                    Optional.of(new ScanSpec("students", Optional.empty(), Optional.empty(), Optional.of("s"))),
                    scan.predicate(), scan.projection(), scan.join(), scan.sort(), scan.mutation(), scan.createIndex(),
                    scan.limit(), scan.createTable(), scan.dropTable());
            eq("SCAN students AS s [full scan]\n", new PlanPrinter().print(aliased));
            String join = new PlanPrinter().print(QO.parseQuery("SELECT * FROM students JOIN dept ON students.dept_id = dept.dept_id").root().orElseThrow());
            check(join.contains("JOIN THETA algorithm=BLOCK_NESTED_LOOP ON"), join);
            String cart = new PlanPrinter().print(QO.parseQuery("SELECT * FROM students, dept").root().orElseThrow());
            check(cart.contains("CARTESIAN algorithm=BLOCK_NESTED_LOOP"), cart);
        });
        test("PlanPrinter shows IndexCondition for INDEX_SCAN", () -> {
            Value five = new Value(DataType.INTEGER, Optional.of(5), Optional.empty(), Optional.empty());
            Value nine = new Value(DataType.INTEGER, Optional.of(9), Optional.empty(), Optional.empty());
            String eqText = new PlanPrinter().print(PlanNodes.indexScan("students", "idx_id", new IndexCondition(IndexOperator.EQ, five, Optional.empty(), true, true)));
            eq("INDEX_SCAN students [index scan] using idx_id IndexCondition(EQ, key=5, lowerInclusive=true, upperInclusive=true)\n", eqText);
            String between = new PlanPrinter().print(PlanNodes.indexScan("students", "idx_id", new IndexCondition(IndexOperator.BETWEEN, five, Optional.of(nine), true, false)));
            eq("INDEX_SCAN students [index scan] using idx_id IndexCondition(BETWEEN, key=5, upper=9, lowerInclusive=true, upperInclusive=false)\n", between);
        });
        test("explainQuery before is the bound plan and costs are shown for both stages", () -> {
            String text = QO.explainQuery("SELECT name FROM students WHERE id = 5");
            String before = text.substring(text.indexOf("== Before =="), text.indexOf("== After =="));
            check(before.contains("students.name") && before.contains("Estimated cost: "), text);
            check(text.substring(text.indexOf("== After ==")).contains("Estimated cost: "), text);
            check(before.contains("(rows~"), text);
            double[] c = explainCosts(text);
            check(c[1] <= c[0], "after " + c[1] + " must not exceed before " + c[0]);
        });
        test("explainQuery with an index shows INDEX_SCAN and IndexCondition; cost does not increase", () -> {
            QueryOptimizer indexed = new QueryOptimizer(MockStorageManager.create(
                    List.of(new IndexMetadata("idx_id", "students", "id", IndexType.HASH))));
            String text = indexed.explainQuery("SELECT name FROM students WHERE id = 5");
            String after = text.substring(text.indexOf("== After =="));
            check(after.contains("INDEX_SCAN students [index scan] using idx_id IndexCondition(EQ, key=5"), text);
            check(text.substring(0, text.indexOf("== After ==")).contains("[full scan]"), text);
            double[] c = explainCosts(text);
            check(c[1] <= c[0], "after " + c[1] + " must not exceed before " + c[0]);
        });
        test("explainQuery works for join, INSERT, UPDATE, DELETE and CREATE INDEX", () -> {
            for (String sql : new String[] {
                    "SELECT students.name, dept.dname FROM students JOIN dept ON students.dept_id = dept.dept_id",
                    "INSERT INTO students VALUES (1, 'Ada', 3.5, 2)",
                    "UPDATE students SET gpa = gpa + 0.1 WHERE id = 1",
                    "DELETE FROM students WHERE id = 1",
                    "CREATE INDEX idx_name ON students(name) USING BPLUS_TREE" }) {
                String text = QO.explainQuery(sql);
                double[] c = explainCosts(text);
                check(c[0] >= 0 && c[1] >= 0, sql + "\n" + text);
                if (sql.startsWith("SELECT")) check(c[1] <= c[0], sql + " after " + c[1] + " > before " + c[0]);
            }
        });
        test("explainQuery does not change optimizeQuery or getCost", () -> {
            ParsedQuery q = QO.parseQuery("SELECT students.name FROM students JOIN dept ON students.dept_id = dept.dept_id WHERE students.id = 5");
            QueryPlan first = QO.optimizeQuery(q);
            QO.explainQuery("SELECT students.name FROM students JOIN dept ON students.dept_id = dept.dept_id WHERE students.id = 5");
            QueryPlan second = QO.optimizeQuery(q);
            eq(first.estimatedCost(), second.estimatedCost());
            eq(new PlanPrinter().print(first.root()), new PlanPrinter().print(second.root()));
            eq(first.estimatedCost(), QO.getCost(first));
        });
        test("explainQuery shows before, after and cost", () -> {
            String text = QO.explainQuery("SELECT name FROM students WHERE id = 5");
            check(text.contains("== Before ==") && text.contains("== After ==") && text.contains("Estimated cost: "), text);
        });
        test("optimizeQuery returns a positive cost for SELECT", () -> {
            QueryPlan plan = QO.optimizeQuery(QO.parseQuery("SELECT * FROM students"));
            check(plan.estimatedCost() > 0 && plan.estimatedCost() == QO.getCost(plan), "cost=" + plan.estimatedCost());
        });
        test("transaction statements are not optimized", () -> {
            ParsedQuery begin = QO.parseQuery("BEGIN");
            error(ErrorCode.INVALID_STATE, () -> QO.optimizeQuery(begin));
        });
        errorAt("SELEC * FROM t", ErrorCode.SYNTAX_ERROR, 0);
        errorAt("SELECT * FORM t", ErrorCode.SYNTAX_ERROR, 9);
        errorAt("SELECT a FROM t WHERE x = 1 2", ErrorCode.SYNTAX_ERROR, 28);
        errorAt("SELECT a FROM t alias", ErrorCode.SYNTAX_ERROR, 16);
        errorAt("SELECT * FROM a LEFT JOIN b ON a.x = b.x", ErrorCode.UNSUPPORTED_OPERATION, 16);
        errorAt("SELECT * FROM a RIGHT JOIN b ON a.x = b.x", ErrorCode.UNSUPPORTED_OPERATION, 16);
        errorAt("SELECT * FROM a LEFT OUTER JOIN b ON a.x = b.x", ErrorCode.UNSUPPORTED_OPERATION, 16);
        errorAt("SELECT * FROM a FULL OUTER JOIN b ON a.x = b.x", ErrorCode.UNSUPPORTED_OPERATION, 16);
        errorAt("SELECT * FROM a JOIN b ON a.x = b.x LEFT JOIN c ON b.x = c.x", ErrorCode.UNSUPPORTED_OPERATION, 36);
        test("outer join message names the join, not aliases", () -> {
            for (String kw : new String[] { "LEFT", "RIGHT", "OUTER", "FULL" }) {
                try { QO.parseQuery("SELECT * FROM a " + kw + " JOIN b ON a.x = b.x"); throw new AssertionError("expected error for " + kw); }
                catch (QoException e) {
                    eq(ErrorCode.UNSUPPORTED_OPERATION, e.getInfo().code());
                    check(e.getMessage().contains("LEFT/RIGHT/FULL/OUTER JOIN is not supported"), e.getMessage());
                    check(!e.getMessage().contains("alias"), e.getMessage());
                }
            }
        });
        test("alias without AS is a syntax error that says AS is required", () -> {
            try { QO.parseQuery("SELECT a FROM t alias"); throw new AssertionError("expected error"); }
            catch (QoException e) { eq(ErrorCode.SYNTAX_ERROR, e.getInfo().code()); check(e.getMessage().contains("requires AS"), e.getMessage()); }
        });
        errorAt("SELECT a FROM t WHERE x = 1 OR y = 2", ErrorCode.UNSUPPORTED_OPERATION, 28);
        test("LIMIT parses to LIMIT(PROJECT(SORT(FILTER(SCAN)))) and LIMIT 0 is valid", () -> {
            PlanNode root = QO.parseQuery("SELECT name FROM students WHERE id > 1 ORDER BY name LIMIT 5").root().orElseThrow();
            PlanNodes.validate(root);
            eq(PlanNodeKind.LIMIT, root.kind());
            eq(5L, root.limit().orElseThrow().count());
            eq(PlanNodeKind.PROJECT, root.children().get(0).kind());
            eq(PlanNodeKind.SORT, root.children().get(0).children().get(0).kind());
            eq(PlanNodeKind.FILTER, root.children().get(0).children().get(0).children().get(0).kind());
            eq(0L, QO.parseQuery("SELECT * FROM students LIMIT 0").root().orElseThrow().limit().orElseThrow().count());
            eq(7L, QO.parseQuery("SELECT * FROM students limit 7;").root().orElseThrow().limit().orElseThrow().count());
        });
        errorAt("SELECT * FROM students LIMIT -1", ErrorCode.SYNTAX_ERROR, 29);
        errorAt("SELECT * FROM students LIMIT 1.5", ErrorCode.SYNTAX_ERROR, 29);
        errorAt("SELECT * FROM students LIMIT x", ErrorCode.SYNTAX_ERROR, 29);
        errorAt("SELECT * FROM students LIMIT", ErrorCode.SYNTAX_ERROR, 28);
        errorAt("SELECT * FROM students LIMIT 5 6", ErrorCode.SYNTAX_ERROR, 31);
        errorAt("SELECT * FROM students LIMIT 99999999999999999999", ErrorCode.INVALID_VALUE, 29);
        errorAt("SELECT * FROM students LIMIT 5 ORDER BY id", ErrorCode.SYNTAX_ERROR, 31);
        errorAt("INSERT INTO students VALUES (1, 'a', 1.0, 1) LIMIT 1", ErrorCode.SYNTAX_ERROR, 45);
        test("table alias with AS fills ScanSpec.alias in every FROM form", () -> {
            for (String sql : new String[] {
                    "SELECT * FROM students AS s",
                    "SELECT * FROM students AS s, dept AS d",
                    "SELECT * FROM students AS s JOIN dept AS d ON s.dept_id = d.dept_id",
                    "SELECT * FROM students AS s NATURAL JOIN dept AS d" }) {
                List<String> aliases = new ArrayList<>();
                collectAliases(QO.parseQuery(sql).root().orElseThrow(), aliases);
                check(aliases.contains("s"), sql + " -> " + aliases);
                if (!sql.endsWith("AS s")) check(aliases.equals(List.of("s", "d")), sql + " -> " + aliases);
            }
            eq(List.of("s"), aliasesOf("SELECT s.id FROM students AS s WHERE s.id = 1"));
        });
        test("PlanPrinter shows LIMIT and table alias for parsed queries", () -> {
            String text = new PlanPrinter().print(QO.parseQuery("SELECT s.id FROM students AS s LIMIT 3").root().orElseThrow());
            eq("LIMIT 3\n  PROJECT [s.id]\n    SCAN students AS s [full scan]\n", text);
        });
        errorAt("SELECT id AS x FROM students", ErrorCode.UNSUPPORTED_OPERATION, 10);
        errorAt("SELECT * FROM students AS", ErrorCode.SYNTAX_ERROR, 25);
        errorAt("SELECT * FROM students AS WHERE", ErrorCode.SYNTAX_ERROR, 26);
        test("LIMIT works end-to-end through optimizeQuery (LIMIT 5 and LIMIT 0)", () -> {
            for (long n : new long[] { 5, 0 }) {
                QueryPlan plan = QO.optimizeQuery(QO.parseQuery("SELECT name FROM students WHERE id > 10 ORDER BY name LIMIT " + n));
                PlanNodes.validate(plan.root());
                eq(PlanNodeKind.LIMIT, plan.root().kind());
                eq(n, plan.root().limit().orElseThrow().count());
                check(plan.estimatedCost() > 0 && plan.estimatedCost() == QO.getCost(plan), "cost=" + plan.estimatedCost());
                String text = QO.explainQuery("SELECT name FROM students WHERE id > 10 ORDER BY name LIMIT " + n);
                check(text.contains("LIMIT " + n), text);
            }
        });
        test("empty input is rejected", () -> error(ErrorCode.SYNTAX_ERROR, () -> QO.parseQuery("   ")));
        test("syntax errors never reach the catalog", () -> {
            int[] calls = {0};
            QueryOptimizer counted = new QueryOptimizer((sqlike.contract.IStorageManager) java.lang.reflect.Proxy.newProxyInstance(
                    QO1Test.class.getClassLoader(), new Class<?>[] { sqlike.contract.IStorageManager.class },
                    (p, m, a) -> { calls[0]++; return m.invoke(MockStorageManager.create(), a); }));
            calls[0] = 0;
            error(ErrorCode.SYNTAX_ERROR, () -> counted.parseQuery("SELECT FROM students"));
            eq(0, calls[0]);
        });
        test("scenario file", () -> eq(0, runScenarios(DEFAULT_SCENARIOS, false)));
        System.out.printf("QO1: %d passed, %d failed%n", passed, failed);
        return failed;
    }

    /** Runs semua "name | expected | SQL" line; returns jumlah dri failing scenarios */
    static int runScenarios(Path file, boolean verbose) {
        List<String> lines;
        try { lines = Files.readAllLines(file); }
        catch (IOException e) { System.out.println("Cannot read scenario file " + file + ": " + e.getMessage()); return 1; }
        int total = 0;
        List<String> failures = new ArrayList<>();
        List<IndexMetadata> indexes = new ArrayList<>();
        QueryOptimizer qo = QO;
        for (String line : lines) {
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            if (line.strip().startsWith("@index ")) {   // "@index <table> <column> <HASH|BPLUS_TREE>": applies to the scenarios below it
                String[] d = line.strip().split("\s+");
                indexes.add(new IndexMetadata("idx_" + d[1] + "_" + d[2], d[1], d[2], IndexType.valueOf(d[3].toUpperCase())));
                qo = new QueryOptimizer(MockStorageManager.create(indexes));
                continue;
            }
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) { failures.add("malformed line: " + line); total++; continue; }
            String name = parts[0].strip(), expected = parts[1].strip(), sql = parts[2].strip();
            total++;
            String actual;
            String output = "";
            try { output = qo.explainQuery(sql); actual = "OK"; }
            catch (QoException e) { actual = e.getInfo().code().name(); output = e.getInfo().code() + e.getInfo().position().map(p -> " at " + p).orElse("") + ": " + e.getMessage() + "\n"; }
            catch (RuntimeException e) { actual = e.getClass().getSimpleName(); output = e + "\n"; }
            boolean known = expected.startsWith("XFAIL ");
            boolean ok = (known ? expected.substring(6).strip() : expected).equals(actual);
            if (verbose) {
                System.out.println("-- " + name + ": " + sql);
                System.out.print(output);
                System.out.println(ok ? (known ? "KNOWN ISSUE " : "PASS ") + name : "FAIL " + name + " (expected " + expected + ", got " + actual + ")");
                System.out.println();
            }
            if (!ok) failures.add(name + " (expected " + expected + ", got " + actual + ")");
        }
        System.out.printf("Scenarios: %d total, %d passed, %d failed%n", total, total - failures.size(), failures.size());
        failures.forEach(f -> System.out.println("  FAIL " + f));
        return failures.size();
    }

    private static void errorAt(String sql, ErrorCode code, int position) {
        test("error " + code + " @" + position + ": " + sql, () -> {
            try { QO.parseQuery(sql); throw new AssertionError("expected " + code); }
            catch (QoException e) {
                eq(code, e.getInfo().code());
                eq(ComponentType.QO, e.getInfo().component());
                eq(Optional.of(position), e.getInfo().position());
            }
        });
    }

    private static void error(ErrorCode expected, Runnable action) {
        try { action.run(); } catch (QoException e) { eq(expected, e.getInfo().code()); return; }
        throw new AssertionError("expected " + expected);
    }

    /** {before, after} costs parsed from explainQuery output (the first two "Estimated cost:" lines). */
    private static void collectAliases(PlanNode node, List<String> out) {
        node.scan().flatMap(ScanSpec::alias).ifPresent(out::add);
        node.children().forEach(child -> collectAliases(child, out));
    }

    private static List<String> aliasesOf(String sql) {
        List<String> aliases = new ArrayList<>();
        collectAliases(QO.parseQuery(sql).root().orElseThrow(), aliases);
        return aliases;
    }

    private static double[] explainCosts(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Estimated cost: (\\S+)").matcher(text);
        double[] costs = new double[2];
        for (int i = 0; i < 2; i++) {
            if (!m.find()) throw new AssertionError("missing cost #" + (i + 1) + " in:\n" + text);
            costs[i] = Double.parseDouble(m.group(1));
        }
        return costs;
    }

    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }

    private static void test(String name, Runnable action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }
}
