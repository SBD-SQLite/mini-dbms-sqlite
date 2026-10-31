package sqlike.qp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sqlike.shared.ErrorCode;
import sqlike.shared.SortDirection;
import sqlike.shared.SortSpec;

/** shortcut: M1 uses preordered output rows, read and sort child input in the next milestone. */
public class SortOperator extends AbstractUnaryOperator {
    private SortSpec spec;
    private RowComparator comparator;
    private List<RowEnvelope> rows;
    private int position;
    private List<RowEnvelope> mockRows;
    private boolean childOpened;

    public SortOperator(Operator child, SortSpec spec, RowComparator comparator, Schema schema) {
        super(child, schema);
        this.spec = Objects.requireNonNull(spec);
        this.comparator = Objects.requireNonNull(comparator);
        SortDirection direction = spec.direction() == null ? SortDirection.ASC : spec.direction();
        if (!schema.equals(child.schema()) || comparator.getKeyOffset() != schema.findColumnOffset(spec.column())
                || comparator.getDirection() != direction) {
            throw new IllegalArgumentException("Sort fixture schema/comparator must match SortSpec");
        }
        rows = new ArrayList<>();
        position = 0;
    }

    public SortSpec getSpec() {
        return spec;
    }

    public RowComparator getComparator() {
        return comparator;
    }

    public void setMockRows(List<RowEnvelope> rows) {
        if (getState() != OperatorState.NEW && getState() != OperatorState.CLOSED) {
            throw new QpException(ErrorCode.INVALID_STATE, "Close sort before changing its fixture");
        }
        List<RowEnvelope> copy = List.copyOf(rows);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i).row().size() != schema().columnCount()) {
                throw new IllegalArgumentException("Sort fixture row width must match schema");
            }
            if (i > 0 && comparator.compare(copy.get(i - 1), copy.get(i)) > 0) {
                throw new IllegalArgumentException("M1 sort rows must already follow SortSpec order");
            }
        }
        mockRows = copy;
    }

    @Override
    protected void doOpen() {
        if (mockRows == null || childOpened) {
            throw new QpException(ErrorCode.INVALID_STATE, "Register M1 sort rows and close the previous scan before open");
        }
        rows.clear();
        position = 0;
        try {
            childOpened = true;
            child.open(requireContext());
            materializeInput();
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

    @Override
    protected Optional<RowEnvelope> doNext() {
        return position < rows.size() ? Optional.of(rows.get(position++)) : Optional.empty();
    }

    @Override
    protected void doClose() {
        try {
            if (childOpened) {
                childOpened = false;
                child.close();
            }
        } finally {
            rows.clear();
            position = 0;
        }
    }

    private void materializeInput() {
        rows.addAll(mockRows);
    }

}

