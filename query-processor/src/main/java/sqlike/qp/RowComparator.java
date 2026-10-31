package sqlike.qp;
import java.util.Comparator;
import java.util.Objects;
import sqlike.shared.ColumnRef;
import sqlike.shared.SortDirection;

public class RowComparator implements Comparator<RowEnvelope> {
    private int keyOffset;
    private SortDirection direction;
    private IValueComparator values;

    public RowComparator(Schema schema, ColumnRef column, SortDirection direction, IValueComparator values) {
        keyOffset = Objects.requireNonNull(schema).findColumnOffset(column);
        this.direction = direction == null ? SortDirection.ASC : direction;
        this.values = Objects.requireNonNull(values);
    }

    public int getKeyOffset() {
        return keyOffset;
    }

    public SortDirection getDirection() {
        return direction;
    }

    public IValueComparator getValues() {
        return values;
    }

    @Override
    public int compare(RowEnvelope left, RowEnvelope right) {
        int result = Integer.signum(values.compare(left.row().getValue(keyOffset), right.row().getValue(keyOffset)));
        return direction == SortDirection.DESC ? -result : result;
    }

}
