package sqlike.frm.checkpoint;

import java.time.Instant;
import java.util.Objects;

public record SnapshotEntry(
        String snapshotId,
        long baseLSN,
        long checkpointLSN,
        Instant createdAt) {

    public SnapshotEntry {
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        if (baseLSN < 0) {
            throw new IllegalArgumentException("baseLSN must be non-negative");
        }
        if (checkpointLSN <= baseLSN) {
            throw new IllegalArgumentException("checkpointLSN must be greater than baseLSN");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
