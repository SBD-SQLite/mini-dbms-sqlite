package sqlike.qp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import sqlike.shared.Value;

public class RowSizeEstimator {

    public RowSizeEstimator() {}

    public long estimate(RowEnvelope row) {
        Objects.requireNonNull(row);
        long bytes = 16;
        for (Value value : row.row().values()) {
            bytes = Math.addExact(bytes, 4 + valueBytes(value));
        }
        return Math.addExact(bytes, row.rid().isPresent() ? 16 : 0);
    }

    private long valueBytes(Value value) {
        return switch (value.type()) {
            case INTEGER -> { value.integerValue().orElseThrow(); yield 4; }
            case FLOAT -> {
                if (!Double.isFinite(value.floatValue().orElseThrow())) {
                    throw new IllegalArgumentException("Non-finite value cannot be estimated");
                }
                yield 8;
            }
            case CHAR, VARCHAR -> 4L + value.textValue().orElseThrow().getBytes(StandardCharsets.UTF_8).length;
        };
    }

}