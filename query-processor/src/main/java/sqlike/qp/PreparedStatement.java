package sqlike.qp;

import sqlike.shared.PlanNode;
import sqlike.shared.QueryPlan;

/**
 * Prepared statement holding the optimized query plan and original SQL statement.
 */
public record PreparedStatement(String sql, QueryPlan plan) {
    public PreparedStatement {
        if (plan == null) {
            throw new IllegalArgumentException("QueryPlan cannot be null in PreparedStatement");
        }
    }

    public static PreparedStatement of(QueryPlan plan) {
        return new PreparedStatement("", plan);
    }

    public static PreparedStatement of(String sql, QueryPlan plan) {
        return new PreparedStatement(sql, plan);
    }

    public PlanNode root() {
        return plan.root();
    }
}
