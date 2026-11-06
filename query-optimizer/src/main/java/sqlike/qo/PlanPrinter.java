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

    public String printBeforeAfter(PlanNode before, PlanNode after, double cost) {
        return "== Before ==\n" + print(before) + "== After ==\n" + print(after) + "Estimated cost: " + cost + "\n";
    }

    private void render(PlanNode n, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(describe(n));
        n.estimatedRows().ifPresent(r -> sb.append("  (rows~").append(r).append(")"));
        sb.append('\n');
        for (PlanNode c : n.children()) render(c, depth + 1, sb);
    }

    private String describe(PlanNode n) {
        return switch (n.kind()) {
            case SCAN -> "SCAN " + n.scan().get().tableName();
            case INDEX_SCAN -> "INDEX_SCAN " + n.scan().get().tableName() + " using " + n.scan().get().indexName().orElse("?");
            case FILTER -> "FILTER " + expr(n.predicate().get());
            case PROJECT -> "PROJECT [" + n.projection().stream().map(PlanPrinter::col).collect(Collectors.joining(", ")) + "]";
            case JOIN -> "JOIN " + n.join().get().joinType()
                    + n.join().get().condition().map(c -> " ON " + expr(c)).orElse("")
                    + (n.join().get().commonColumns().isEmpty() ? "" : " common=" + n.join().get().commonColumns());
            case CARTESIAN -> "CARTESIAN";
            case SORT -> "SORT " + col(n.sort().get().column()) + " " + n.sort().get().direction();
            case INSERT -> "INSERT INTO " + n.mutation().get().tableName() + " (" + n.mutation().get().insertValues().size() + " values)";
            case UPDATE -> "UPDATE " + n.mutation().get().tableName() + " SET "
                    + n.mutation().get().updateColumn().map(PlanPrinter::col).orElse("?") + " = "
                    + n.mutation().get().updateExpression().map(PlanPrinter::expr).orElse("?");
            case DELETE -> "DELETE FROM " + n.mutation().get().tableName();
            case CREATE_INDEX -> "CREATE_INDEX " + n.createIndex().get().indexName() + " ON "
                    + n.createIndex().get().tableName() + "(" + n.createIndex().get().columnName() + ") " + n.createIndex().get().indexType();
        };
    }

    static String col(ColumnRef c) { return c.tableName().map(t -> t + ".").orElse("") + c.columnName(); }

    static String expr(Expression e) {
        return switch (e.kind()) {
            case LITERAL -> lit(e.literal().get());
            case COLUMN -> col(e.column().get());
            case UNARY -> "-(" + expr(e.operands().get(0)) + ")";
            case BINARY -> "(" + expr(e.operands().get(0)) + " " + op(e.operator().get()) + " " + expr(e.operands().get(1)) + ")";
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
