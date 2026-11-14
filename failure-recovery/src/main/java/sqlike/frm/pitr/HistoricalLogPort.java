package sqlike.frm.pitr;

import java.util.List;

import sqlike.frm.log.LogRecord;

/**
 * Write-ahead-log history a point-in-time recovery needs.
 *
 * <p>The log only provides and separates records; choosing the target LSN, the base snapshot,
 * and the committed changes to replay belongs to {@link HistoricalRecoveryPlanner}.
 */
public interface HistoricalLogPort {

    /**
     * Returns every valid record of the archived logs and the active log, in ascending LSN order.
     * Needs LSN, txId, type, COMMIT time, and before/after images.
     */
    List<LogRecord> readHistory();

    /**
     * Archives the active log and opens a new one that starts with a CHECKPOINT marker for
     * {@code snapshotId}. LSNs continue after the last one, and records after {@code targetLSN}
     * belong to the discarded timeline. Called after a historical restore so crash recovery
     * never redoes that timeline.
     */
    void separateHistory(String snapshotId, long targetLSN);
}
