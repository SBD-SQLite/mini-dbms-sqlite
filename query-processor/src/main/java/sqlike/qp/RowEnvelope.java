package sqlike.qp;

import java.util.Optional;
import sqlike.shared.RID;

/**
 * Envelope carrying an in-flight row through the query operator pipeline,
 * optionally preserving record identity (RID) for mutation operations.
 */
public record RowEnvelope(Row row, Optional<RID> rid) {
    public RowEnvelope {
        if (row == null) {
            throw new IllegalArgumentException("Row cannot be null in RowEnvelope");
        }
        rid = rid == null ? Optional.empty() : rid;
    }

    public static RowEnvelope of(Row row) {
        return new RowEnvelope(row, Optional.empty());
    }

    public static RowEnvelope of(Row row, RID rid) {
        return new RowEnvelope(row, Optional.ofNullable(rid));
    }
}
