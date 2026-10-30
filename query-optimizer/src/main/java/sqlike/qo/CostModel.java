package sqlike.qo;

import sqlike.contract.IStorageManager;
import sqlike.shared.PlanNode;


public class CostModel {
    private final IStorageManager storage;
    public CostModel(IStorageManager storage) { this.storage = storage; }
    public double getCost(PlanNode root) { return 0.0;}
}
