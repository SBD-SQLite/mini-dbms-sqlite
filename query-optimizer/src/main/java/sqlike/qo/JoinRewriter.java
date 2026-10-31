package sqlike.qo;

import sqlike.shared.PlanNode;

public class JoinRewriter {
    public PlanNode cartesianToJoin(PlanNode plan) {
        if (plan == null) throw QoErrors.invalidState("cartesianToJoin requires a plan");
        return plan;
    }

    public PlanNode mergeSelectionIntoJoin(PlanNode plan) {
        if (plan == null) throw QoErrors.invalidState("mergeSelectionIntoJoin requires a plan");
        return plan;
    }
}
