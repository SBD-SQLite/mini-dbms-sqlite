package sqlike.qo;

import java.util.List;
import sqlike.shared.PlanNode;

public class PlanSelector {
    public PlanNode pickCheapest(List<PlanNode> c, CostModel cost) {
        if (c == null || c.isEmpty())
            throw QoErrors.invalidState("pickCheapest requires at least one candidate");
        for (PlanNode candidate : c) {
            if (candidate == null)
                throw QoErrors.invalidState("pickCheapest candidates must not contain null");
        }
        if (cost == null) throw QoErrors.invalidState("pickCheapest requires a cost model");
        return c.get(0);
    }
}
