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
     * Moves the active log into the archive and opens a new active log that continues LSNs after
     * the last one. Called after a historical restore so crash recovery never redoes the
     * discarded timeline.
     */
    void separateHistory();
}
