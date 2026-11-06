package sqlike.qo;

import java.util.List;
import java.util.Optional;
import sqlike.shared.*;

public final class QO2Test {
    private static int passed;
    private static int failed;
    private static final Lexer LEXER = new Lexer();
    private static final QueryParser PARSER = new QueryParser(new SelectParser(new ExpressionParser()), new StatementParser());

    public static void main(String[] args) {
        if (run() != 0) System.exit(1);
    }

    public static int run() {
        passed = 0;
        failed = 0;
        test("multiplication precedence", () -> eq("(5 + (3 * 2))", expr("5 + 3 * 2")));
        test("left associativity", () -> eq("((5 - 3) - 2)", expr("5 - 3 - 2")));
        test("division associativity", () -> eq("((12 / 3) / 2)", expr("12 / 3 / 2")));
        test("division before subtraction", () -> eq("(12 - (6 / 2))", expr("12 - 6 / 2")));
        test("unary plus and minus", () -> eq("(a + -2)", expr("+a + -2")));
        test("parentheses", () -> eq("((5 + 3) * 2)", expr("(5 + 3) * 2")));
        test("AND precedence and qualified columns", () -> eq("((a.id = b.id) AND ((salary * 1.05) >= 5000))", expr("a.id = b.id AND salary * 1.05 >= 5000")));
        for (String op : List.of("=", "<>", ">", ">=", "<", "<="))
            test("comparison " + op, () -> eq("(a " + op + " 1)", expr("a " + op + " 1")));
        test("text literal", () -> eq("(name = 'O'Brien')", expr("name = 'O''Brien'")));
        test("negative integer boundary", () -> eq("-2147483648", expr("-2147483648")));
        test("unary expression", () -> eq("-((a + 1))", expr("-(a + 1)")));
        test("grouped predicates", () -> eq("((a = 1) AND (b < 2))", expr("(a = 1) AND (b < 2)")));
        test("SELECT shares expression grammar", () -> {
            PlanNode filter = parse("SELECT id FROM employees WHERE salary * 1.05 >= 5000").root().orElseThrow().children().get(0);
            eq("((salary * 1.05) >= 5000)", PlanPrinter.expr(filter.predicate().orElseThrow()));
        });
        test("JOIN shares expression grammar", () -> {
            PlanNode join = parse("SELECT a.id FROM a JOIN b ON a.id = b.id AND a.x > 1").root().orElseThrow().children().get(0);
            eq("((a.id = b.id) AND (a.x > 1))", PlanPrinter.expr(join.join().orElseThrow().condition().orElseThrow()));
        });
        test("UPDATE expression and filter", () -> {
            PlanNode n = parse("UPDATE employees SET salary = 1.05 * salary WHERE id = 1").root().orElseThrow();
            eq(PlanNodeKind.UPDATE, n.kind());
            eq("(1.05 * salary)", PlanPrinter.expr(n.mutation().orElseThrow().updateExpression().orElseThrow()));
            eq(PlanNodeKind.FILTER, n.children().get(0).kind());
        });
        test("UPDATE without WHERE", () -> eq(PlanNodeKind.SCAN, parse("UPDATE employees SET salary = 0").root().orElseThrow().children().get(0).kind()));
        test("INSERT values", () -> eq(3, parse("INSERT INTO employees VALUES (1, 'Ada', -2.5)").root().orElseThrow().mutation().orElseThrow().insertValues().size()));
        test("DELETE filter", () -> eq(PlanNodeKind.FILTER, parse("DELETE FROM employees WHERE id = 1").root().orElseThrow().children().get(0).kind()));
        test("DELETE without WHERE", () -> eq(PlanNodeKind.SCAN, parse("DELETE FROM employees").root().orElseThrow().children().get(0).kind()));
        for (String sql : List.of("BEGIN", "BEGIN TRANSACTION", "COMMIT", "ABORT"))
            test(sql, () -> {
                ParsedQuery p = parse(sql);
                eq(true, p.root().isEmpty());
                eq(sql.split(" ")[0], p.transactionCommand().orElseThrow().action().name());
                eq(sql.split(" ")[0], p.statementType().name());
            });
        test("CREATE INDEX default", () -> eq(IndexType.HASH, parse("CREATE INDEX ix ON employees(id)").root().orElseThrow().createIndex().orElseThrow().indexType()));
        test("CREATE INDEX explicit type", () -> eq(IndexType.BPLUS_TREE, parse("CREATE INDEX ix ON employees(id) USING bplus_tree;").root().orElseThrow().createIndex().orElseThrow().indexType()));
        test("INSERT preserves shared Value types and order", () -> {
            ParsedQuery p = parse("INSERT INTO employees VALUES (-2147483648, 1.25, 'Ada')");
            eq(StatementType.INSERT, p.statementType());
            eq(true, p.transactionCommand().isEmpty());
            PlanNode n = p.root().orElseThrow();
            PlanNodes.validate(n);
            eq(0, n.children().size());
            MutationSpec m = n.mutation().orElseThrow();
            eq("employees", m.tableName());
            eq(List.of(
                    new Value(DataType.INTEGER, Optional.of(Integer.MIN_VALUE), Optional.empty(), Optional.empty()),
                    new Value(DataType.FLOAT, Optional.empty(), Optional.of(1.25), Optional.empty()),
                    new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("Ada"))), m.insertValues());
            eq(true, m.updateColumn().isEmpty());
            eq(true, m.updateExpression().isEmpty());
        });
        test("UPDATE matches MutationSpec and FILTER SCAN contract", () -> {
            ParsedQuery p = parse("UPDATE employees SET salary = salary + 1 WHERE employees.id = 2");
            eq(StatementType.UPDATE, p.statementType());
            PlanNode n = p.root().orElseThrow();
            PlanNodes.validate(n);
            MutationSpec m = n.mutation().orElseThrow();
            eq(new ColumnRef(Optional.of("employees"), "salary"), m.updateColumn().orElseThrow());
            eq(List.of(), m.insertValues());
            Expression e = m.updateExpression().orElseThrow();
            eq(ExpressionKind.BINARY, e.kind());
            eq(Optional.of(ExpressionOperator.ADD), e.operator());
            eq(Optional.of(new ColumnRef(Optional.empty(), "salary")), e.operands().get(0).column());
            eq(true, e.literal().isEmpty() && e.column().isEmpty() && e.subquery().isEmpty());
            PlanNode filter = n.children().get(0);
            eq(PlanNodeKind.FILTER, filter.kind());
            eq(PlanNodeKind.SCAN, filter.children().get(0).kind());
            eq("employees", filter.children().get(0).scan().orElseThrow().tableName());
        });
        test("DELETE matches MutationSpec contract", () -> {
            ParsedQuery p = parse("DELETE FROM employees WHERE employees.id = 1");
            eq(StatementType.DELETE, p.statementType());
            PlanNode n = p.root().orElseThrow();
            PlanNodes.validate(n);
            eq(new MutationSpec("employees", List.of(), Optional.empty(), Optional.empty()), n.mutation().orElseThrow());
            eq(true, p.transactionCommand().isEmpty());
        });
        test("CREATE INDEX uses shared metadata slot", () -> {
            ParsedQuery p = parse("CREATE INDEX ix ON employees(id) USING hash");
            eq(StatementType.CREATE_INDEX, p.statementType());
            PlanNode n = p.root().orElseThrow();
            PlanNodes.validate(n);
            eq(PlanNodeKind.CREATE_INDEX, n.kind());
            eq(new IndexMetadata("ix", "employees", "id", IndexType.HASH), n.createIndex().orElseThrow());
            eq(0, n.children().size());
            eq(true, n.mutation().isEmpty());
        });
        test("error matches ErrorInfo contract and source offset", () -> {
            String sql = "DELETE FROM employees WHERE (id = 1 OR id = 2)";
            try { parse(sql); throw new AssertionError("expected rejection"); }
            catch (QoException e) {
                eq(ErrorCode.UNSUPPORTED_OPERATION, e.getInfo().code());
                eq(ComponentType.QO, e.getInfo().component());
                eq(Optional.of(sql.indexOf("OR")), e.getInfo().position());
                eq(e.getInfo().message(), e.getMessage());
            }
        });
        for (String sql : List.of("UPDATE employees salary = 1", "UPDATE employees SET salary = a + * b", "UPDATE employees SET salary = 1 WHERE a >", "INSERT INTO employees (1)", "INSERT INTO employees VALUES ()", "DELETE employees", "BEGIN COMMIT", "CREATE INDEX ix ON employees()", "COMMIT; ABORT",
                "UPDATE employees SET salary = 1, id = 2", "INSERT INTO employees VALUES (1), (2)",
                "INSERT INTO employees VALUES (id)", "CREATE INDEX ix ON employees(id, salary)",
                "CREATE INDEX ix ON employees(id) USING unknown", "DELETE FROM employees WHERE (id = 1"))
            test("reject " + sql, () -> error(ErrorCode.SYNTAX_ERROR, () -> parse(sql)));
        for (String sql : List.of("DELETE FROM employees WHERE NOT id = 1", "DELETE FROM employees WHERE id = NULL",
                "UPDATE employees SET salary = (SELECT salary FROM employees)", "CREATE TABLE employees (id INTEGER)", "DROP TABLE employees"))
            test("unsupported " + sql, () -> error(ErrorCode.UNSUPPORTED_OPERATION, () -> parse(sql)));
        test("integer overflow", () -> error(ErrorCode.INVALID_VALUE, () -> expr("2147483648")));
        test("unsupported OR", () -> error(ErrorCode.UNSUPPORTED_OPERATION, () -> expr("a = 1 OR b = 2")));
        test("unsupported OR inside parentheses", () -> error(ErrorCode.UNSUPPORTED_OPERATION, () -> expr("(a = 1 OR b = 2)")));
        System.out.printf("QO2: %d passed, %d failed%n", passed, failed);
        return failed;
    }
    static ParsedQuery parse(String sql) { return PARSER.parse(LEXER.tokenize(sql), sql); }
    static String expr(String sql) {
        TokenStream ts = LEXER.tokenize(sql);
        Expression e = new ExpressionParser().parseExpression(ts);
        ts.expectEndOfStatement();
        return PlanPrinter.expr(e);
    }
    static void error(ErrorCode expected, Runnable action) {
        try { action.run(); } catch (QoException e) { eq(expected, e.getInfo().code()); return; }
        throw new AssertionError("expected " + expected);
    }
    static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }
    static void test(String name, Runnable action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }
}
