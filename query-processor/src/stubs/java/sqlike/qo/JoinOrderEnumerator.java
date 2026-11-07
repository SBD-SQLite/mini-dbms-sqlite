package sqlike.qo;

import java.util.List;
import sqlike.shared.PlanNode;

public class JoinOrderEnumerator {
    public List<PlanNode> enumerate(PlanNode plan) {
        if (plan == null) throw QoErrors.invalidState("enumerate requires a plan");
        return List.of(plan);
    }
}
