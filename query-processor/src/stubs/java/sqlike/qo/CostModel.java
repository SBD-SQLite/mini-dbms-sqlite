package sqlike.qo;

import sqlike.contract.IStorageManager;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;

/** Cost-model skeleton. Costs are intended to be elapsed I/O time in milliseconds. */
public final class CostModel {
    private static final double SEEK_TIME_MS = 4.0;
    private static final double TRANSFER_TIME_MS = 0.1;
    // TODO: replace with the actual buffer budget once the contract exposes it.
    private static final int BUFFER_BLOCKS = 3;

    private final IStorageManager storage;
    private final CardinalityEstimator estimator;

    public CostModel(IStorageManager storage) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        this.storage = storage;
        this.estimator = new CardinalityEstimator(storage);
    }

    /** Sum each node's local cost and the costs of its child plans. */
    public double getCost(PlanNode root) {
        if (root == null) throw QoErrors.invalidValue("plan root is null", 0);
        return 1.0;
    }

    private double costNode(PlanNode node) {
        double children = node.children().stream().mapToDouble(this::costNode).sum();
        return children + switch (node.kind()) {
            case SCAN -> costScan(node);
            case INDEX_SCAN -> costIndexScan(node);
            case FILTER -> costFilter(node);
            case PROJECT -> costProject(node);
            case JOIN, CARTESIAN -> costJoinBNLJ(node);
            case SORT -> costSort(node);
            case INSERT -> costInsert(node);
            case UPDATE -> costUpdate(node);
            case DELETE -> costDelete(node);
            case CREATE_INDEX -> costCreateIndex(node);
        };
    }

    private double costScan(PlanNode node) {
        // TODO: use b_r from SM statistics. Sequential scan estimate: one initial
        // seek plus b_r block transfers, i.e. tS + b_r * tt (zero if b_r is zero).
        throw new UnsupportedOperationException("TODO: implement table-scan cost");
    }

    private double costIndexScan(PlanNode node) {
        // TODO: estimate index traversal and qualifying heap-page reads. The SM
        // contract currently has no tree height or clustering statistic, so agree
        // on and document a conservative assumption instead of guessing silently.
        throw new UnsupportedOperationException("TODO: implement index-scan cost");
    }

    private double costFilter(PlanNode node) {
        // TODO: under a pipelined I/O-only model, filtering adds no disk I/O;
        // return zero unless the plan materializes filter output. CPU cost is out
        // of scope for the seek/transfer model.
        throw new UnsupportedOperationException("TODO: implement filter cost");
    }

    private double costProject(PlanNode node) {
        // TODO: projection is normally pipelined and adds no disk I/O. Return zero
        // unless projection causes a materialized intermediate result.
        throw new UnsupportedOperationException("TODO: implement projection cost");
    }

    private double costJoinBNLJ(PlanNode node) {
        // TODO: use b_outer + ceil(b_outer / (M - 2)) * b_inner block transfers
        // for BNLJ, plus seeks for the actual access pattern. Since costNode sums
        // child costs, define carefully whether those child reads are already
        // charged, so the first scan of either input is not counted twice.
        throw new UnsupportedOperationException("TODO: implement BNLJ cost");
    }

    private double costSort(PlanNode node) {
        // TODO: external merge-sort estimate: 2*b*(1 + ceil(log_(M-1)(ceil(b/M))))
        // block transfers, plus seeks for run generation and merge passes.
        throw new UnsupportedOperationException("TODO: implement sort cost");
    }

    private double costInsert(PlanNode node) {
        // TODO: estimate target-page access and write/transfer cost. Use the
        // inserted row and table statistics if the SM contract exposes enough data.
        throw new UnsupportedOperationException("TODO: implement INSERT cost");
    }

    private double costUpdate(PlanNode node) {
        // TODO: include the cost to locate qualifying rows and write changed
        // pages; avoid charging child-plan reads twice.
        throw new UnsupportedOperationException("TODO: implement UPDATE cost");
    }

    private double costDelete(PlanNode node) {
        // TODO: include the cost to locate qualifying rows and modify/delete their
        // pages; avoid charging child-plan reads twice.
        throw new UnsupportedOperationException("TODO: implement DELETE cost");
    }

    private double costCreateIndex(PlanNode node) {
        // TODO: estimate scanning the table and writing/building index pages.
        // Use table/index statistics when available; otherwise document the model.
        throw new UnsupportedOperationException("TODO: implement CREATE INDEX cost");
    }
}
