package sqlike.frm.pitr;

import java.util.Objects;

/** Resolved snapshot and WAL boundary for one historical recovery request. */
public record HistoricalRecoveryPlan(String snapshotId, long targetLSN, long maxTxId) {

    public HistoricalRecoveryPlan {
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        if (targetLSN < 0) {
            throw new IllegalArgumentException("targetLSN must be non-negative");
        }
        if (maxTxId < 0) {
            throw new IllegalArgumentException("maxTxId must be non-negative");
        }
    }
}
