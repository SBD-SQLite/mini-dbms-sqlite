package join;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BlockNestedLoopJoinOperator extends AbstractBinaryOperator {
    private JoinSpec spec;
    private JoinLayout layout;
    private IExpressionEvaluator evaluator;
    private IValueComparator comparator;
    private RowSizeEstimator estimator;
    private List<RowEnvelope> outerBatch;
    private Optional<RowEnvelope> pendingOuter;
    private Optional<RowEnvelope> currentInner;
    private int outerIndex;
    private long batchBytes;
    private long maxOuterBytes;
    private boolean outerEnded;

    public BlockNestedLoopJoinOperator(Operator left, Operator right, JoinSpec spec, JoinLayout layout, IExpressionEvaluator evaluator, IValueComparator comparator, RowSizeEstimator estimator, Schema schema) {
        super(left, right, schema);
        this.spec = spec;
        this.layout = layout;
        this.evaluator = evaluator;
        this.comparator = comparator;
        this.estimator = estimator;
        outerBatch = new ArrayList<>();
        pendingOuter = Optional.empty();
        currentInner = Optional.empty();
        outerIndex = 0;
        batchBytes = 0;
        maxOuterBytes = 0;
        outerEnded = false;
    }

    public JoinSpec getSpec() {
        return spec;
    }

    public JoinLayout getLayout() {
        return layout;
    }

    public IExpressionEvaluator getEvaluator() {
        return evaluator;
    }

    public IValueComparator getComparator() {
        return comparator;
    }

    public RowSizeEstimator getEstimator() {
        return estimator;
    }

    protected void doOpen() {throw new UnsupportedOperationException("BNLJ belum diimplementasikan");}

    protected Optional<RowEnvelope> doNext() {throw new UnsupportedOperationException("BNLJ belum diimplementasikan");}

    protected void doClose() {throw new UnsupportedOperationException("BNLJ belum diimplementasikan");}

    private boolean fillOuterBatch() {throw new UnsupportedOperationException("BNLJ belum diimplementasikan");}

    private void reopenInner() {throw new UnsupportedOperationException("BNLJ belum diimplementasikan");}

    private boolean matches(RowEnvelope left, RowEnvelope right) {throw new UnsupportedOperationException("BNLJ belum diimplementasikan");}

    private boolean naturalMatches(RowEnvelope left, RowEnvelope right) {throw new UnsupportedOperationException("BNLJ belum diimplementasikan");}

    private void clearBatch() {throw new UnsupportedOperationException("BNLJ belum diimplementasikan");}

}

class AbstractBinaryOperator {
    protected Operator left;
    protected Operator right;
    protected Schema outputSchema;

    protected AbstractBinaryOperator(Operator left, Operator right, Schema outputSchema) {
        this.left = left;
        this.right = right;
        this.outputSchema = outputSchema;
    }
}