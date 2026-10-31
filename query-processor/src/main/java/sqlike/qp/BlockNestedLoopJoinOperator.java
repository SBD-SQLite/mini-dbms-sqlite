package sqlike.qp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sqlike.shared.ErrorCode;
import sqlike.shared.JoinAlgorithm;
import sqlike.shared.JoinSpec;
import sqlike.shared.JoinType;

/** shortcut: M1 uses registered output rows, execute BNLJ against children in the next milestone. */
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
    private List<RowEnvelope> mockRows;
    private int mockPosition;

    public BlockNestedLoopJoinOperator(Operator left, Operator right, JoinSpec spec, JoinLayout layout, IExpressionEvaluator evaluator, IValueComparator comparator, RowSizeEstimator estimator, Schema schema) {
        super(left, right, schema);
        this.spec = Objects.requireNonNull(spec);
        this.layout = Objects.requireNonNull(layout);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.comparator = Objects.requireNonNull(comparator);
        this.estimator = Objects.requireNonNull(estimator);
        if (spec.algorithm() != JoinAlgorithm.BLOCK_NESTED_LOOP || spec.joinType() == null
                || !schema.equals(layout.getOutputSchema())) {
            throw new IllegalArgumentException("Join fixture requires BNLJ and the layout output schema");
        }
        if (spec.joinType() == JoinType.THETA && spec.condition().isEmpty()) {
            throw new IllegalArgumentException("THETA fixture requires a bound join condition");
        }
        var columns = new ArrayList<>(left.schema().columns());
        columns.addAll(right.schema().columns());
        if (!columns.equals(layout.getInputSchema().columns())) {
            throw new IllegalArgumentException("Join layout must match both child schemas");
        }
        for (String column : spec.commonColumns()) {
            left.schema().findColumnOffset(null, column);
            right.schema().findColumnOffset(null, column);
        }
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

    public void setMockRows(List<RowEnvelope> rows) {
        if (getState() != OperatorState.NEW && getState() != OperatorState.CLOSED) {
            throw new QpException(ErrorCode.INVALID_STATE, "Close join before changing its fixture");
        }
        List<RowEnvelope> copy = List.copyOf(rows);
        for (RowEnvelope row : copy) {
            if (row.row().size() != schema().columnCount() || row.rid().isPresent()) {
                throw new IllegalArgumentException("Join fixture requires output-width rows without a physical RID");
            }
        }
        mockRows = copy;
    }

    @Override
    protected void doOpen() {
        if (mockRows == null) {
            throw new QpException(ErrorCode.INVALID_STATE, "Register M1 join rows before open");
        }
        clearBatch();
        pendingOuter = Optional.empty();
        currentInner = Optional.empty();
        outerEnded = false;
        mockPosition = 0;
        super.doOpen();
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        return mockPosition < mockRows.size() ? Optional.of(mockRows.get(mockPosition++)) : Optional.empty();
    }

    @Override
    protected void doClose() {
        try {
            super.doClose();
        } finally {
            clearBatch();
            pendingOuter = Optional.empty();
            currentInner = Optional.empty();
            mockPosition = 0;
            outerEnded = false;
        }
    }

    // shortcut: helper algoritma masih dummy M1, aktifkan saat BNLJ penuh mulai dikerjakan.
    private boolean fillOuterBatch() {return false;}

    private void reopenInner() {}

    private boolean matches(RowEnvelope left, RowEnvelope right) {return false;}

    private boolean naturalMatches(RowEnvelope left, RowEnvelope right) {return false;}

    private void clearBatch() {
        outerBatch.clear();
        batchBytes = 0;
        outerIndex = 0;
    }
}
