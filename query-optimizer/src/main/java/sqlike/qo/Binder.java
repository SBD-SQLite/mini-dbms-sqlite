package sqlike.qo;

import sqlike.contract.IStorageManager;
import sqlike.shared.*;

public class Binder {
    private final IStorageManager storage;
    public Binder(IStorageManager storage) { this.storage = storage; }

    public PlanNode bind(ParsedQuery query) {
        return query.root().orElseThrow(() -> QoErrors.invalidState("statement has no plan root"));
    }
}
