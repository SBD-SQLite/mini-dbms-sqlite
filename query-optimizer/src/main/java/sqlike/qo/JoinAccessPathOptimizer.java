package sqlike.qo;

import java.util.List;
import java.util.LinkedHashSet;
import sqlike.contract.IStorageManager;
import sqlike.shared.PlanNode;

/** QO-11 through QO-14: equivalent join/access alternatives and final QO3 cost comparison. */
public class JoinAccessPathOptimizer {
    private final JoinRewriter joinRewriter = new JoinRewriter();
    private final JoinOrderEnumerator joinEnumerator = new JoinOrderEnumerator();
    private final PlanSelector planSelector = new PlanSelector();
    private final CostModel costModel;
    private final AccessPathOptimizer accessOptimizer;

    /** Supply the same QO3 model used by the facade; no catalog or cost formula is duplicated. */
    public JoinAccessPathOptimizer(CostModel costModel) {
        if (costModel == null) throw new IllegalArgumentException("cost model is required");
        this.costModel = costModel;
        this.accessOptimizer = null;
    }

    /** Full pipeline; the one-argument constructor remains available for join-only consumers. */
    public JoinAccessPathOptimizer(CostModel costModel, IStorageManager storage) {
        if (costModel == null) throw new IllegalArgumentException("cost model is required");
        this.costModel = costModel;
        this.accessOptimizer = new AccessPathOptimizer(storage);
    }

    public PlanNode optimize(PlanNode plan) {
        QO5Plans.requirePlan(plan);
        if (!isOptimizable(plan)) return plan;

        PlanNode rewritten = joinRewriter.mergeSelectionIntoJoin(joinRewriter.cartesianToJoin(plan));
        var candidates = new LinkedHashSet<PlanNode>();
        for (PlanNode candidate : generateCandidates(rewritten))
            candidates.add(QO5Plans.simplifyIdentityProjects(restoreOutputSchema(candidate, plan)));
        // Include the incoming baseline too; equivalent rewritten plans win equal-cost ties.
        candidates.add(QO5Plans.simplifyIdentityProjects(plan));
        PlanNode candidate = planSelector.pickCheapest(List.copyOf(candidates), costModel);
        PlanNode result = QO5Plans.simplifyIdentityProjects(restoreOutputSchema(candidate, plan));
        PlanNodes.validate(result);
        return result;
    }

    private List<PlanNode> generateCandidates(PlanNode plan) {
        var candidates = new LinkedHashSet<PlanNode>();
        for (PlanNode joinCandidate : joinEnumerator.enumerate(plan)) {
            candidates.add(joinCandidate);
            if (accessOptimizer != null)
                candidates.add(accessOptimizer.chooseAccessPath(joinCandidate, costModel));
        }
        return List.copyOf(candidates);
    }

    private PlanNode restoreOutputSchema(PlanNode candidate, PlanNode original) {
        return QO5Plans.restore(candidate, original);
    }

    private boolean isOptimizable(PlanNode plan) {
        return switch (plan.kind()) {
            case SCAN, INDEX_SCAN, FILTER, PROJECT, JOIN, CARTESIAN, SORT, LIMIT -> true;
            case UPDATE, DELETE -> accessOptimizer != null;
            case INSERT, CREATE_INDEX, CREATE_TABLE, DROP_TABLE -> false;
        };
    }
}
