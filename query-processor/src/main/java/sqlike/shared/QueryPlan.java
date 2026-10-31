package sqlike.shared;

public record QueryPlan(PlanNode root, double estimatedCost) {
}
