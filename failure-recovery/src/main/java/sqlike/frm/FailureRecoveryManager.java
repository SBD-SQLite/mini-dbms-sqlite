package sqlike.frm;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;
import sqlike.shared.RecoveryResult;
import sqlike.shared.RecoveryTarget;

/**
 * Single entry point of FRM (FR1). Forwards every request to the module that owns it.
 *
 * <p>Milestone 1 MOCK: the FR2-FR5 modules are not wired yet. Every method is callable, logging
 * returns increasing positive LSNs, and the TODO in each method names the delegate.
 */
public class FailureRecoveryManager implements IFailureRecoveryManager {

    private final SmPort sm;
    private final CcmPort ccm;
    private final String logPath;
    private final AtomicLong mockLastLsn = new AtomicLong(0);
    private volatile RecoveryState state = RecoveryState.NOT_STARTED;

    // TODO(FR2): private final LogManager logManager;
    // TODO(FR3): private final RollbackManager rollbackManager;
    // TODO(FR4): private final CheckpointManager checkpointManager;
    // TODO(FR4): private final CheckpointScheduler checkpointScheduler;
    // TODO(FR4): private final PointInTimeRecovery pitr;
    // TODO(FR5): private final RecoveryManager recoveryManager;

    public FailureRecoveryManager(SmPort sm, CcmPort ccm, String logPath) {
        this.sm = sm;
        this.ccm = ccm;
        this.logPath = logPath;
    }

    @Override
    public long logBegin(long txId) {
        ensureReady();
        // MOCK: TODO(FR2): return logManager.logBegin(txId);
        return mockLastLsn.incrementAndGet();
    }

    @Override
    public long logChange(long txId, DataChange change) {
        ensureReady();
        // MOCK: TODO(FR2): return logManager.logChange(txId, change);
        return mockLastLsn.incrementAndGet();
    }

    @Override
    public void logCommit(long txId) {
        ensureReady();
        // MOCK: TODO(FR2): logManager.logCommit(txId); returns only once COMMIT is durable.
        mockLastLsn.incrementAndGet();
    }

    @Override
    public void logAbort(long txId) {
        ensureReady();
        // MOCK: TODO(FR2): logManager.logAbort(txId); called only after rollback succeeds.
        mockLastLsn.incrementAndGet();
    }

    @Override
    public void ensureDurable(long lsn) {
        // MOCK: no-op. TODO(FR2): logManager.ensureDurable(lsn); SM calls this before writing a page.
    }

    @Override
    public void rollback(long txId) {
        ensureReady();
        // MOCK: no-op. TODO(FR3): rollbackManager.rollback(txId);
    }

    @Override
    public RecoveryResult recover() {
        state = RecoveryState.RUNNING;
        // MOCK: TODO(FR5): RecoveryResult result = recoveryManager.recover(); on failure state = FAILED.
        RecoveryResult result = new RecoveryResult(0L, 0L, mockLastLsn.get(), Optional.empty());
        state = RecoveryState.READY;
        return result;
    }

    @Override
    public void checkpoint() {
        ensureReady();
        // MOCK: TODO(FR4): checkpointManager.checkpoint();
        mockLastLsn.incrementAndGet();
    }

    @Override
    public void recoverTo(RecoveryTarget target) {
        ensureReady();
        // MOCK: no-op. TODO(FR4): pitr.recoverTo(target);
    }

    /** Returns the log in LSN order, optionally only one transaction's records (FR-03). */
    public String showLogs(Optional<Long> txId) {
        // MOCK: TODO(FR2): return logManager.showLogs(txId);
        return "";
    }

    public RecoveryState getState() {
        return state;
    }

    /** Rejects requests until startup recovery has finished (FR-07). */
    private void ensureReady() {
        if (state != RecoveryState.READY) {
            throw new IllegalStateException("FRM is not ready: recovery state is " + state);
        }
    }
}
