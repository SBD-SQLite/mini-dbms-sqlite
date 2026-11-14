package sqlike.contract;

import sqlike.shared.DataChange;
import sqlike.shared.RecoveryResult;
import sqlike.shared.SchemaChange;
import sqlike.shared.RecoveryTarget;

/**
 * FRM contract from the SQLike supergroup report (section 2.1), owned by the FRM group.
 *
 * <p>This is the authoritative definition; CCM keeps an identical copy to compile against.
 */
public interface IFailureRecoveryManager extends IWalDurability {

    /** Logs the start of a transaction and returns its LSN. */
    long logBegin(long txId);

    /** Logs one record change (operation, table, RID, before/after image) and returns its LSN. */
    long logChange(long txId, DataChange change);

    /** Logs COMMIT and makes every change record of the transaction plus COMMIT durable. */
    void logCommit(long txId);

    /** Logs that the abort finished, after every undo succeeded. */
    void logAbort(long txId);

    /** Makes the log durable up to {@code lsn}; failures are reported to the caller. */
    @Override
    void ensureDurable(long lsn);

    /** Undoes the transaction's changes in reverse order using the log. */
    void rollback(long txId);

    /** Crash recovery: redo committed changes, undo unfinished ones. */
    RecoveryResult recover();

    /** Creates a checkpoint once active transactions finish and the needed log and data are durable. */
    void checkpoint();

    /** Restores the database to a point in time or to a committed transaction, in maintenance mode. */
    void recoverTo(RecoveryTarget target);

    /**
     * Logs a schema change (CREATE/DROP TABLE, CREATE INDEX, CASCADE FK update) and returns its
     * LSN (PRD v1.0 §3.5).
     *
     * <p>The default refuses loudly on purpose. The copies QP and CCM compile against return a
     * fixed {@code 1L}, so an implementation that forgets to override this looks like it logged a
     * record while the DDL never reaches the WAL. Reconcile the three copies when the root
     * {@code contracts/} source set is created.
     */
    default long logSchemaChange(long txId, SchemaChange change) {
        throw new UnsupportedOperationException("logSchemaChange is not implemented");
    }
}
