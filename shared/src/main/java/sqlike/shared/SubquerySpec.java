package sqlike.shared;

public record SubquerySpec(
        SubqueryType type,
        PlanNode root,
        boolean correlated
) {
}
