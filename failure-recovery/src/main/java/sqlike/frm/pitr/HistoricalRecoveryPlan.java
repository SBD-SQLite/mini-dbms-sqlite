package sqlike.frm.pitr;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import sqlike.frm.log.LogRecord;

/**
 * Snapshot to restore and committed changes in {@code (baseLSN, targetLSN]} to replay.
 *
 * @param maxTxId highest txId in the whole history, including abandoned ranges
 */
public record HistoricalRecoveryPlan(
        String snapshotId,
        long baseLSN,
        long targetLSN,
        long maxTxId,
        Set<Long> committedTxIds,
        List<LogRecord> changesToReplay) {

    public HistoricalRecoveryPlan {
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        if (baseLSN < 0 || targetLSN < baseLSN) {
            throw new IllegalArgumentException("require 0 <= baseLSN <= targetLSN");
        }
        if (maxTxId < 0) {
            throw new IllegalArgumentException("maxTxId must be non-negative");
        }
        committedTxIds = Set.copyOf(committedTxIds);
        changesToReplay = List.copyOf(changesToReplay);
    }
}
