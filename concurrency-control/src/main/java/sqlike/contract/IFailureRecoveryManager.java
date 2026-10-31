package sqlike.contract;

import sqlike.shared.DataChange;
import sqlike.shared.RecoveryResult;
import sqlike.shared.RecoveryTarget;

/**
 * FRM contract from the SQLike supergroup report (section 2.1), owned by the FRM group.
 *
 * <p>Copied here only so CCM can compile against it; the FRM group's definition is authoritative.
 */
public interface IFailureRecoveryManager {

    /** Logs the start of a transaction and returns its LSN. */
    long logBegin(long txId);

    /** Logs one record change (operation, table, RID, before/after image) and returns its LSN. */
    long logChange(long txId, DataChange change);

    /** Logs COMMIT and makes every change record of the transaction plus COMMIT durable. */
    void logCommit(long txId);

    /** Logs that the abort finished, after every undo succeeded. */
    void logAbort(long txId);

    /** Makes the log durable up to {@code lsn}; failures are reported to the caller. */
    void ensureDurable(long lsn);

    /** Undoes the transaction's changes in reverse order using the log. */
    void rollback(long txId);

    /** Crash recovery: redo committed changes, undo unfinished ones. */
    RecoveryResult recover();

    /** Creates a checkpoint once active transactions finish and the needed log and data are durable. */
    void checkpoint();

    /** Restores the database to a point in time or to a committed transaction, in maintenance mode. */
    void recoverTo(RecoveryTarget target);
}
