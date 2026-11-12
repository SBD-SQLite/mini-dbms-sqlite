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
            eq("PROJECT [name]\n  FILTER (id = 5)\n    SCAN students\n", text);
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
        errorAt("SELECT a FROM t alias", ErrorCode.UNSUPPORTED_OPERATION, 16);
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
        test("table alias message is unchanged", () -> {
            try { QO.parseQuery("SELECT a FROM t alias"); throw new AssertionError("expected error"); }
            catch (QoException e) { check(e.getMessage().contains("table alias"), e.getMessage()); }
        });
        errorAt("SELECT a FROM t WHERE x = 1 OR y = 2", ErrorCode.UNSUPPORTED_OPERATION, 28);
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
        for (String line : lines) {
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) { failures.add("malformed line: " + line); total++; continue; }
            String name = parts[0].strip(), expected = parts[1].strip(), sql = parts[2].strip();
            total++;
            String actual;
            String output = "";
            try { output = QO.explainQuery(sql); actual = "OK"; }
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

    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }

    private static void test(String name, Runnable action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }
}
