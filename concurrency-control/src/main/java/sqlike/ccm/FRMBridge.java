package sqlike.ccm;

import java.util.concurrent.atomic.AtomicLong;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.shared.DataChange;

/**
 * Forwards transaction events, data changes, and rollbacks to FRM following WAL (CC3).
 *
 * <p>Milestone 1 MOCK: every method is callable but does not call FRM yet. {@link #logWrite} returns
 * increasing positive LSNs so a granted write can carry a valid changeLSN.
 */
public class FRMBridge {

    private final IFailureRecoveryManager recovery;
    private final AtomicLong mockLastLsn = new AtomicLong(0);

    public FRMBridge(IFailureRecoveryManager recovery) {
        this.recovery = recovery;
    }

    /** Logs BEGIN for the transaction (CC-03). */
    public void logBegin(long txId) {
        // MOCK: no-op. TODO(CC3): recovery.logBegin(txId); a logging failure aborts the transaction.
    }

    /** Logs one granted write and returns its change LSN; must succeed before the change is applied (CC-03). */
    public long logWrite(long txId, DataChange change) {
        // MOCK: TODO(CC3): return recovery.logChange(txId, change); on failure the write is not applied.
        return mockLastLsn.incrementAndGet();
    }

    /** Returns only after the COMMIT record is durable (CC-03). */
    public void logCommit(long txId) {
        // MOCK: no-op. TODO(CC3): recovery.logCommit(txId); on failure the transaction is not COMMITTED.
    }

    /** Undoes the transaction, then records that the abort finished (CC-04). */
    public void rollbackThenLogAbort(long txId) {
        // MOCK: no-op. TODO(CC3): recovery.rollback(txId), then recovery.logAbort(txId) once undo succeeds.
    }
}
