package sqlike.qo;

import java.util.List;
import sqlike.shared.PlanNode;
public class JoinAccessPathOptimizer {
    public PlanNode optimize(PlanNode plan) {
        if (plan == null || plan.kind() == null)
            throw QoErrors.invalidState("QO5 requires a plan with a node kind");
        if (!isOptimizable(plan)) return plan;

        PlanNode candidate = generateCandidates(plan).get(0);
        return restoreOutputSchema(candidate, plan);
    }

    private List<PlanNode> generateCandidates(PlanNode plan) {
        return List.of(plan);
    }

    private PlanNode restoreOutputSchema(PlanNode candidate, PlanNode original) {
        return candidate;
    }

    private boolean isOptimizable(PlanNode plan) {
        return switch (plan.kind()) {
            case SCAN, INDEX_SCAN, FILTER, PROJECT, JOIN, CARTESIAN, SORT -> true;
            case INSERT, UPDATE, DELETE, CREATE_INDEX -> false;
        };
    }
}
