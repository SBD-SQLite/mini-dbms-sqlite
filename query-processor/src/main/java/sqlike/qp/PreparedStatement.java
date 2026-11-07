package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.AccessRequest;
import sqlike.qp.port.QpContracts.ResourceKey;
import sqlike.qp.port.QpContracts.TableId;
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
        this(sql, plan, extractAccessRequests(plan));
    }

    public PreparedStatement(List<AccessRequest> accesses) {
        this("", null, accesses);
    }

    public static PreparedStatement of(QueryPlan plan) {
        return new PreparedStatement("", plan, extractAccessRequests(plan));
    }

    public static PreparedStatement of(String sql, QueryPlan plan) {
        return new PreparedStatement(sql, plan, extractAccessRequests(plan));
    }

    public static PreparedStatement of(List<AccessRequest> accesses) {
        return new PreparedStatement("", null, accesses);
    }

    public PlanNode root() {
        return plan != null ? plan.root() : null;
    }

    private static List<AccessRequest> extractAccessRequests(QueryPlan plan) {
        if (plan == null || plan.root() == null) {
            return List.of();
        }
        List<AccessRequest> list = new ArrayList<>();
        collectAccessRequests(plan.root(), list);
        return List.copyOf(list);
    }

    private static void collectAccessRequests(PlanNode node, List<AccessRequest> out) {
        if (node == null) {
            return;
        }
        switch (node.kind()) {
            case SCAN, INDEX_SCAN -> node.scan().ifPresent(s -> {
                if (s.tableName() != null && !s.tableName().isBlank()) {
                    out.add(new AccessRequest(ResourceKey.table(new TableId(s.tableName())), AccessMode.READ));
                }
            });
            case INSERT, UPDATE -> node.mutation().ifPresent(m -> {
                if (m.tableName() != null && !m.tableName().isBlank()) {
                    out.add(new AccessRequest(ResourceKey.table(new TableId(m.tableName())), AccessMode.WRITE));
                }
            });
            case DELETE -> {
                String table = node.mutation().map(m -> m.tableName())
                        .or(() -> node.scan().map(s -> s.tableName()))
                        .orElse(null);
                if (table != null && !table.isBlank()) {
                    out.add(new AccessRequest(ResourceKey.table(new TableId(table)), AccessMode.WRITE));
                }
            }
            case CREATE_INDEX -> node.createIndex().ifPresent(ci -> {
                if (ci.tableName() != null && !ci.tableName().isBlank()) {
                    out.add(new AccessRequest(ResourceKey.table(new TableId(ci.tableName())), AccessMode.WRITE));
                }
            });
            case CREATE_TABLE -> node.createTable().ifPresent(ct -> {
                if (ct.tableName() != null && !ct.tableName().isBlank()) {
                    out.add(new AccessRequest(ResourceKey.table(new TableId(ct.tableName())), AccessMode.WRITE));
                }
            });
            case DROP_TABLE -> node.dropTable().ifPresent(dt -> {
                if (dt.tableName() != null && !dt.tableName().isBlank()) {
                    out.add(new AccessRequest(ResourceKey.table(new TableId(dt.tableName())), AccessMode.WRITE));
                }
            });
            default -> {
            }
        }
        node.predicate().ifPresent(expr -> collectExpressionAccessRequests(expr, out));
        node.join().flatMap(sqlike.shared.JoinSpec::condition)
                .ifPresent(expr -> collectExpressionAccessRequests(expr, out));
        node.mutation().flatMap(sqlike.shared.MutationSpec::updateExpression)
                .ifPresent(expr -> collectExpressionAccessRequests(expr, out));
        if (node.children() != null) {
            for (PlanNode child : node.children()) {
                collectAccessRequests(child, out);
            }
        }
    }

    private static void collectExpressionAccessRequests(sqlike.shared.Expression expr, List<AccessRequest> out) {
        if (expr == null) {
            return;
        }
        if (expr.kind() == sqlike.shared.ExpressionKind.SUBQUERY) {
            expr.subquery().ifPresent(sub -> collectAccessRequests(sub.root(), out));
        }
        if (expr.operands() != null) {
            for (var operand : expr.operands()) {
                collectExpressionAccessRequests(operand, out);
            }
        }
    }
}
