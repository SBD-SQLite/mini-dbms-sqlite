package sort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SortOperator extends AbstractUnaryOperator {
    private SortSpec spec;
    private RowComparator comparator;
    private List<RowEnvelope> rows;
    private int position;

    public SortOperator(Operator child, SortSpec spec, RowComparator comparator, Schema schema) {
        super(child, schema);
        this.spec = spec;
        this.comparator = comparator;
        rows = new ArrayList<>();
        position = 0;
    }

    public SortSpec getSpec() {
        return spec;
    }

    public RowComparator getComparator() {
        return comparator;
    }

    protected void doOpen() {throw new UnsupportedOperationException("Sort belum diimplementasikan");}

    protected Optional<RowEnvelope> doNext() {throw new UnsupportedOperationException("Sort belum diimplementasikan");}

    protected void doClose() {throw new UnsupportedOperationException("Sort belum diimplementasikan");}

    private void materializeInput() {throw new UnsupportedOperationException("Sort belum diimplementasikan");}

}
