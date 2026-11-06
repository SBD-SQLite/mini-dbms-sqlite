package sqlike.qo;

import java.util.List;
import sqlike.shared.PlanNode;

/** QO-11/QO-12 pipeline. Access-path alternatives are added in the next QO5 package. */
public class JoinAccessPathOptimizer {
    private final JoinRewriter joinRewriter = new JoinRewriter();
    private final JoinOrderEnumerator joinEnumerator = new JoinOrderEnumerator();
    private final PlanSelector planSelector = new PlanSelector();
    private final CostModel costModel;

    /** Supply the same QO3 model used by the facade; no catalog or cost formula is duplicated. */
    public JoinAccessPathOptimizer(CostModel costModel) {
        if (costModel == null) throw new IllegalArgumentException("cost model is required");
        this.costModel = costModel;
    }

    public PlanNode optimize(PlanNode plan) {
        QO5Plans.requirePlan(plan);
        if (!isOptimizable(plan)) return plan;

        PlanNode rewritten = joinRewriter.mergeSelectionIntoJoin(joinRewriter.cartesianToJoin(plan));
        PlanNode candidate = planSelector.pickCheapest(generateCandidates(rewritten), costModel);
        PlanNode result = restoreOutputSchema(candidate, plan);
        PlanNodes.validate(result);
        return result;
    }

    private List<PlanNode> generateCandidates(PlanNode plan) {
        return joinEnumerator.enumerate(plan);
    }

    private PlanNode restoreOutputSchema(PlanNode candidate, PlanNode original) {
        return QO5Plans.restore(candidate, original);
    }

    private boolean isOptimizable(PlanNode plan) {
        return switch (plan.kind()) {
            case SCAN, INDEX_SCAN, FILTER, PROJECT, JOIN, CARTESIAN, SORT, LIMIT -> true;
            case INSERT, UPDATE, DELETE, CREATE_INDEX, CREATE_TABLE, DROP_TABLE -> false;
        };
    }
}
