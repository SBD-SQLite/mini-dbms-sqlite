package sqlike.qp;

import java.util.List;
import sqlike.qp.port.QpContracts.AccessRequest;
import sqlike.shared.PlanNode;
import sqlike.shared.QueryPlan;

/**
 * Prepared statement holding the optimized query plan, original SQL statement, and access requests.
 */
public record PreparedStatement(String sql, QueryPlan plan, List<AccessRequest> accesses) {
    public PreparedStatement {
        accesses = accesses == null ? List.of() : List.copyOf(accesses);
    }

    public PreparedStatement(String sql, QueryPlan plan) {
        this(sql, plan, List.of());
    }

    public PreparedStatement(List<AccessRequest> accesses) {
        this("", null, accesses);
    }

    public static PreparedStatement of(QueryPlan plan) {
        return new PreparedStatement("", plan, List.of());
    }

    public static PreparedStatement of(String sql, QueryPlan plan) {
        return new PreparedStatement(sql, plan, List.of());
    }

    public static PreparedStatement of(List<AccessRequest> accesses) {
        return new PreparedStatement("", null, accesses);
    }

    public PlanNode root() {
        return plan != null ? plan.root() : null;
    }
}
