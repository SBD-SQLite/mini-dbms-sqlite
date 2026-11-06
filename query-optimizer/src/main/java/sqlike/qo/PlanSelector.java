package sqlike.qo;

import java.util.List;
import sqlike.shared.PlanNode;

/** Minimum-cost selection using QO3, with stable first-candidate tie breaking. */
public class PlanSelector {
    public PlanNode pickCheapest(List<PlanNode> c, CostModel cost) {
        if (c == null || c.isEmpty())
            throw QoErrors.invalidState("pickCheapest requires at least one candidate");
        for (PlanNode candidate : c) {
            if (candidate == null)
                throw QoErrors.invalidState("pickCheapest candidates must not contain null");
        }
        if (cost == null) throw QoErrors.invalidState("pickCheapest requires a cost model");
        PlanNode best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (PlanNode candidate : c) {
            double estimate = cost.getCost(candidate);
            if (!Double.isFinite(estimate) || estimate < 0)
                throw QoErrors.invalidState("candidate cost must be finite and nonnegative");
            // Strict inequality keeps the first candidate on ties, including a zero-cost tie.
            if (best == null || estimate < bestCost) {
                best = candidate;
                bestCost = estimate;
            }
        }
        return best;
    }
}
