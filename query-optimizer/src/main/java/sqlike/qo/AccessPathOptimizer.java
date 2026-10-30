package sqlike.qo;

import sqlike.shared.PlanNode;

public class AccessPathOptimizer {
    public PlanNode chooseAccessPath(PlanNode plan, CostModel cost) {
        if (plan == null) throw QoErrors.invalidState("chooseAccessPath requires a plan");
        if (cost == null) throw QoErrors.invalidState("chooseAccessPath requires a cost model");
        return plan;
    }
}
