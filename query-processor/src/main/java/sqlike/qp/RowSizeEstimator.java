package sqlike.qp;
import sqlike.shared.Value;

public class RowSizeEstimator {

    public RowSizeEstimator() {}

    public long estimate(RowEnvelope row) {throw new UnsupportedOperationException("Estimasi ukuran row belum diimplementasikan");}

    private long valueBytes(Value value) {throw new UnsupportedOperationException("Estimasi ukuran nilai belum diimplementasikan");}

}
