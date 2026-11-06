package sqlike.frm.checkpoint;

import java.time.Instant;
import java.util.Objects;

/** Metadata written only after a quiescent checkpoint completes successfully. */
public record CheckpointInfo(
        long checkpointLSN,
        long flushedThroughLSN,
        Instant createdAt,
        String snapshotId) {

    public CheckpointInfo {
        if (checkpointLSN <= 0) {
            throw new IllegalArgumentException("checkpointLSN must be positive");
        }
        if (flushedThroughLSN < 0 || flushedThroughLSN >= checkpointLSN) {
            throw new IllegalArgumentException(
                    "flushedThroughLSN must be non-negative and precede checkpointLSN");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
    }
}
