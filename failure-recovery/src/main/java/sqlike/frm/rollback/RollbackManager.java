package sqlike.frm.rollback;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import sqlike.frm.FrmException;
import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.port.RollbackPort;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;
import sqlike.shared.ErrorCode;

/**
 * Undoes one transaction during normal operation (FR3, FR-04, PRD v1.0 §11).
 *
 * <p>Reads the transaction's log records newest first and writes each before-image back through
 * SM on the same RID with the record's original LSN, so UPDATE returns to the old values, INSERT
 * disappears, and DELETE comes back. Rollback writes no log record: CCM writes the final ABORT
 * with {@code logAbort} after this method returns.
 *
 * <p>Repeating a rollback after a failure part-way is safe, because writing the same
 * before-images again gives the same result while CCM still holds the transaction's locks. Once
 * the ABORT is logged, a repeated rollback changes nothing, since another transaction may already
 * have changed those records.
 */
public class RollbackManager implements RollbackPort {

    private final LogManager logManager;
    private final SmPort storageManager;

    public RollbackManager(LogManager logManager, SmPort storageManager) {
        this.logManager = Objects.requireNonNull(logManager, "logManager");
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    }

    @Override
    public void rollback(long txId) {
        if (txId <= 0) {
            throw new FrmException(ErrorCode.INVALID_VALUE, "txId must be positive: " + txId);
        }
        List<LogRecord> records = logManager.readLogs(txId);
        if (records.isEmpty()) {
            throw new FrmException(ErrorCode.NOT_FOUND, "rollback: unknown transaction " + txId);
        }
        if (has(records, LogType.COMMIT)) {
            throw new FrmException(ErrorCode.INVALID_STATE,
                    "rollback: transaction " + txId + " is committed and cannot be undone");
        }
        if (has(records, LogType.ABORT)) {
            return; // abort already finished
        }
        undoTxChanges(txId);
    }

    /**
     * Writes back the before-image of every change of {@code txId}, newest first.
     * SM failures are not caught: the caller keeps the transaction FAILED and may retry.
     *
     * @return number of changes undone
     */
    public int undoTxChanges(long txId) {
        int undone = 0;
        for (LogRecord record : logManager.readLogsBackward(txId)) {
            if (!record.txID().equals(Optional.of(txId))) {
                continue; // defensive: only this transaction's records
            }
            if (record.type() == LogType.SCHEMA_CHANGE) {
                throw new FrmException(ErrorCode.UNSUPPORTED_OPERATION, "rollback of transaction "
                        + txId + " needs SmPort.restoreSchema for the schema change at LSN "
                        + record.lsn() + ", which is not available yet");
            }
            if (record.change().isEmpty()) {
                continue; // BEGIN and other markers
            }
            DataChange change = record.change().get();
            storageManager.writeAt(change.tableName(), change.rid(), change.before(), record.lsn());
            undone++;
        }
        return undone;
    }

    private static boolean has(List<LogRecord> records, LogType type) {
        for (LogRecord record : records) {
            if (record.type() == type) {
                return true;
            }
        }
        return false;
    }
}
