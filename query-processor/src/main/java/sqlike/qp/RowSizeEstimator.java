package sqlike.qp;

import sqlike.shared.Value;

/**
 * RowSizeEstimator stub matching CLASS DIAGRAM FIX (owned by QP4 role).
 * Provided as a mock placeholder for Milestone 1 dependency wiring.
 */
public class RowSizeEstimator {
    public RowSizeEstimator() {
        // TODO(M1-MOCK): Default constructor
    }

    public long estimate(RowEnvelope row) {
        // TODO(M1-MOCK): Estimate total byte size of row values
        if (row == null || row.row() == null) return 0L;
        long total = 0L;
        for (Value v : row.row().values()) {
            total += valueBytes(v);
        }
        return total;
    }

    private long valueBytes(Value value) {
        // TODO(M1-MOCK): Estimate bytes per value based on DataType
        if (value == null) return 0L;
        return switch (value.type()) {
            case INTEGER -> 4L;
            case FLOAT -> 8L;
            case CHAR, VARCHAR -> value.textValue().map(s -> (long) s.length()).orElse(0L);
        };
    }
}
