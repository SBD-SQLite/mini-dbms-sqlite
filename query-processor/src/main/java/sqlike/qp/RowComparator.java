package sqlike.qp;
import java.util.Comparator;
import sqlike.shared.ColumnRef;
import sqlike.shared.SortDirection;

public class RowComparator implements Comparator<RowEnvelope> {
    private int keyOffset;
    private SortDirection direction;
    private IValueComparator values;

    public RowComparator(Schema schema, ColumnRef column, SortDirection direction, IValueComparator values) {
        keyOffset = schema.findColumnOffset(column);
        this.direction = direction;
        this.values = values;
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
    public int compare(RowEnvelope left, RowEnvelope right) {throw new UnsupportedOperationException("Perbandingan row belum diimplementasikan");}

}
