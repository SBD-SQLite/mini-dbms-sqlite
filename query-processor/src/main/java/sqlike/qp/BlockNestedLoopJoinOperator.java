package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sqlike.shared.ErrorCode;
import sqlike.shared.JoinAlgorithm;
import sqlike.shared.JoinSpec;
import sqlike.shared.JoinType;

public class BlockNestedLoopJoinOperator extends AbstractBinaryOperator {
    private static final long DEFAULT_MAX_OUTER_BYTES = 64L * 1024L;
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

    public BlockNestedLoopJoinOperator(
            Operator left,
            Operator right,
            JoinSpec spec,
            JoinLayout layout,
            IExpressionEvaluator evaluator,
            IValueComparator comparator,
            RowSizeEstimator estimator,
            Schema schema) {
        super(
                left,
                right,
                layout != null && Objects.equals(schema, layout.getOutputSchema())
                        ? layout.getOutputSchema()
                        : schema);
        this.spec = Objects.requireNonNull(spec);
        this.layout = Objects.requireNonNull(layout);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.comparator = Objects.requireNonNull(comparator);
        this.estimator = Objects.requireNonNull(estimator);
        if (spec.algorithm() != JoinAlgorithm.BLOCK_NESTED_LOOP
                || spec.joinType() == null
                || !schema.equals(layout.getOutputSchema())) {
            throw new IllegalArgumentException("Join requires BNLJ and the layout output schema");
        }
        if (spec.joinType() == JoinType.THETA && spec.condition().isEmpty()) {
            throw new IllegalArgumentException("THETA join requires a bound join condition");
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
        maxOuterBytes = DEFAULT_MAX_OUTER_BYTES;
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

    public void setMaxOuterBytes(long maxOuterBytes) {
        if (getState() != OperatorState.NEW && getState() != OperatorState.CLOSED) {
            throw new QpException(ErrorCode.INVALID_STATE, "Close join before changing its buffer budget");
        }
        if (maxOuterBytes <= 0) {
            throw new IllegalArgumentException("maxOuterBytes must be positive");
        }
        this.maxOuterBytes = maxOuterBytes;
    }

    public long getMaxOuterBytes() {
        return maxOuterBytes;
    }

    public void setMockRows(List<RowEnvelope> rows) {
        if (getState() != OperatorState.NEW && getState() != OperatorState.CLOSED) {
            throw new QpException(ErrorCode.INVALID_STATE, "Close join before changing its fixture");
        }
        if (rows == null) {
            mockRows = null;
            return;
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
        requireContext().check();
        clearBatch();
        pendingOuter = Optional.empty();
        currentInner = Optional.empty();
        outerEnded = false;
        mockPosition = 0;
        super.doOpen();
        if (mockRows == null) {
            try {
                if (fillOuterBatch()) {
                    currentInner = right.next();
                    outerIndex = 0;
                }
            } catch (RuntimeException | Error error) {
                try {
                    doClose();
                } catch (RuntimeException | Error cleanupError) {
                    if (cleanupError != error) {
                        error.addSuppressed(cleanupError);
                    }
                }
                throw error;
            }
        }
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        requireContext().check();
        if (mockRows != null) {
            return mockPosition < mockRows.size() ? Optional.of(mockRows.get(mockPosition++)) : Optional.empty();
        }
        while (!outerBatch.isEmpty()) {
            while (currentInner.isPresent()) {
                RowEnvelope rightRow = currentInner.get();
                while (outerIndex < outerBatch.size()) {
                    requireContext().check();
                    RowEnvelope leftRow = outerBatch.get(outerIndex++);
                    if (matches(leftRow, rightRow)) {
                        requireContext().check();
                        return Optional.of(layout.combineForOutput(leftRow, rightRow));
                    }
                }
                outerIndex = 0;
                currentInner = right.next();
            }
            if (!fillOuterBatch()) {
                break;
            }
            reopenInner();
        }
        return Optional.empty();
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

    private boolean fillOuterBatch() {
        requireContext().check();
        clearBatch();
        if (pendingOuter.isPresent()) {
            RowEnvelope pending = pendingOuter.get();
            pendingOuter = Optional.empty();
            long size = outerRowBytes(pending);
            outerBatch.add(pending);
            batchBytes += size;
        }
        while (!outerEnded) {
            requireContext().check();
            Optional<RowEnvelope> nextOuter = left.next();
            if (nextOuter.isEmpty()) {
                outerEnded = true;
                break;
            }
            RowEnvelope row = nextOuter.get();
            long rowBytes = outerRowBytes(row);
            if (rowBytes > maxOuterBytes - batchBytes) {
                pendingOuter = Optional.of(row);
                break;
            }
            outerBatch.add(row);
            batchBytes += rowBytes;
        }
        outerIndex = 0;
        return !outerBatch.isEmpty();
    }

    private void reopenInner() {
        requireContext().check();
        right.close();
        requireContext().check();
        right.open(requireContext());
        currentInner = right.next();
        outerIndex = 0;
    }

    private long outerRowBytes(RowEnvelope row) {
        long bytes = estimator.estimate(row);
        if (bytes > maxOuterBytes) {
            throw new QpException(ErrorCode.INVALID_VALUE,
                    "Join outer row requires " + bytes + " logical bytes, exceeding buffer budget " + maxOuterBytes);
        }
        return bytes;
    }

    private boolean matches(RowEnvelope leftRow, RowEnvelope rightRow) {
        return switch (spec.joinType()) {
            case CARTESIAN -> spec.condition().isEmpty()
                    || evaluator.evaluateCondition(
                            spec.condition().get(),
                            layout.combineForPredicate(leftRow, rightRow),
                            layout.getInputSchema());
            case THETA -> evaluator.evaluateCondition(
                    spec.condition().orElseThrow(() -> new IllegalStateException("THETA join missing condition")),
                    layout.combineForPredicate(leftRow, rightRow),
                    layout.getInputSchema());
            case NATURAL -> naturalMatches(leftRow, rightRow)
                    && (spec.condition().isEmpty()
                            || evaluator.evaluateCondition(
                                    spec.condition().get(),
                                    layout.combineForPredicate(leftRow, rightRow),
                                    layout.getInputSchema()));
        };
    }

    private boolean naturalMatches(RowEnvelope leftRow, RowEnvelope rightRow) {
        for (String colName : spec.commonColumns()) {
            int leftOffset = left.schema().findColumnOffset(null, colName);
            int rightOffset = right.schema().findColumnOffset(null, colName);
            if (!comparator.equal(leftRow.row().getValue(leftOffset), rightRow.row().getValue(rightOffset))) {
                return false;
            }
        }
        return true;
    }

    private void clearBatch() {
        outerBatch.clear();
        batchBytes = 0;
        outerIndex = 0;
    }
}
