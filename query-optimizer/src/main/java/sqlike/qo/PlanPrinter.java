package sqlike.qo;
import java.util.stream.Collectors;
import sqlike.shared.*;

/** renders a plan tree as indented text */
public class PlanPrinter {

    public String print(PlanNode plan) {
        StringBuilder sb = new StringBuilder();
        render(plan, 0, sb);
        return sb.toString();
    }

    public String printWithCost(QueryPlan plan, double cost) {
        return print(plan.root()) + "Estimated cost: " + cost + "\n";
    }

    public String printBeforeAfter(PlanNode before, double costBefore, PlanNode after, double costAfter) {
        return "== Before ==\n" + print(before) + "Estimated cost: " + costBefore + "\n"
                + "== After ==\n" + print(after) + "Estimated cost: " + costAfter + "\n";
    }

    /** Statements that are validated but not optimized: one plan, no before/after stage. */
    public String printNotOptimized(PlanNode plan, double cost) {
        return "== Plan (not optimized) ==\n" + print(plan) + "Estimated cost: " + cost + "\n";
    }

    private void render(PlanNode n, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(describe(n));
        n.estimatedRows().ifPresent(r -> sb.append("  (rows~").append(Math.round(r)).append(")"));
        sb.append('\n');
        for (PlanNode c : n.children()) render(c, depth + 1, sb);
    }

    private String describe(PlanNode n) {
        return switch (n.kind()) {
            case SCAN -> "SCAN " + n.scan().get().tableName() + alias(n.scan().get()) + " [full scan]";
            case INDEX_SCAN -> "INDEX_SCAN " + n.scan().get().tableName() + alias(n.scan().get()) + " [index scan] using "
                    + n.scan().get().indexName().orElse("?")
                    + n.scan().get().condition().map(c -> " " + indexCondition(c)).orElse("");
            case FILTER -> "FILTER " + expr(n.predicate().get());
            case PROJECT -> "PROJECT [" + n.projection().stream().map(PlanPrinter::col).collect(Collectors.joining(", ")) + "]";
            case JOIN -> "JOIN " + n.join().get().joinType() + " algorithm=" + n.join().get().algorithm()
                    + n.join().get().condition().map(c -> " ON " + expr(c)).orElse("")
                    + (n.join().get().commonColumns().isEmpty() ? "" : " common=" + n.join().get().commonColumns());
            case CARTESIAN -> "CARTESIAN algorithm=" + n.join().map(j -> j.algorithm().toString()).orElse("?");
            case SORT -> "SORT " + col(n.sort().get().column()) + " " + n.sort().get().direction();
            case INSERT -> "INSERT INTO " + n.mutation().get().tableName() + " (" + n.mutation().get().insertValues().size() + " values)";
            case UPDATE -> "UPDATE " + n.mutation().get().tableName() + " SET "
                    + n.mutation().get().updateColumn().map(PlanPrinter::col).orElse("?") + " = "
                    + n.mutation().get().updateExpression().map(PlanPrinter::expr).orElse("?");
            case DELETE -> "DELETE FROM " + n.mutation().get().tableName();
            case CREATE_INDEX -> "CREATE_INDEX " + n.createIndex().get().indexName() + " ON "
                    + n.createIndex().get().tableName() + "(" + n.createIndex().get().columnName() + ") " + n.createIndex().get().indexType();
            case LIMIT -> "LIMIT " + n.limit().get().count();
            case CREATE_TABLE -> createTable(n.createTable().get());
            case DROP_TABLE -> "DROP_TABLE " + n.dropTable().get().tableName() + " " + n.dropTable().get().behavior();
        };
    }

    private static String alias(ScanSpec scan) {
        return scan.alias().map(a -> " AS " + a).orElse("");
    }

    private static String indexCondition(IndexCondition c) {
        return "IndexCondition(" + c.operator() + ", key=" + lit(c.key())
                + c.upperBound().map(u -> ", upper=" + lit(u)).orElse("")
                + ", lowerInclusive=" + c.lowerInclusive() + ", upperInclusive=" + c.upperInclusive() + ")";
    }

    private static String createTable(CreateTableSpec t) {
        String columns = t.columns().stream()
                .map(c -> c.name() + " " + c.dataType() + c.maxLength().map(len -> "(" + len + ")").orElse(""))
                .collect(Collectors.joining(", "));
        String keys = (t.primaryKey().isEmpty() ? "" : " PRIMARY KEY " + t.primaryKey())
                + t.foreignKeys().stream()
                        .map(fk -> " FOREIGN KEY " + fk.columnNames() + " REFERENCES " + fk.referencedTable() + fk.referencedColumns())
                        .collect(Collectors.joining());
        return "CREATE_TABLE " + t.tableName() + " (" + columns + ")" + keys;
    }

    private static String subquery(Expression e) {
        SubquerySpec s = e.subquery().get();
        String tag = "<" + s.type() + " subquery" + (s.correlated() ? ", correlated" : "") + ">";
        return s.type() == SubqueryType.IN ? expr(e.operands().get(0)) + " IN " + tag : tag;
    }

    static String col(ColumnRef c) { return c.tableName().map(t -> t + ".").orElse("") + c.columnName(); }

    static String expr(Expression e) {
        return switch (e.kind()) {
            case LITERAL -> lit(e.literal().get());
            case COLUMN -> col(e.column().get());
            case UNARY -> "-(" + expr(e.operands().get(0)) + ")";
            case BINARY -> "(" + expr(e.operands().get(0)) + " " + op(e.operator().get()) + " " + expr(e.operands().get(1)) + ")";
            case SUBQUERY -> subquery(e);
        };
    }

    private static String lit(Value v) {
        return switch (v.type()) {
            case INTEGER -> String.valueOf(v.integerValue().orElse(0));
            case FLOAT -> String.valueOf(v.floatValue().orElse(0.0));
            case CHAR, VARCHAR -> "'" + v.textValue().orElse("") + "'";
        };
    }

    private static String op(ExpressionOperator o) {
        return switch (o) {
            case ADD -> "+"; case SUBTRACT -> "-"; case MULTIPLY -> "*"; case DIVIDE -> "/"; case NEGATE -> "-";
            case EQ -> "="; case NE -> "<>"; case GT -> ">"; case GE -> ">="; case LT -> "<"; case LE -> "<=";
            case AND -> "AND";
        };
    }
}
