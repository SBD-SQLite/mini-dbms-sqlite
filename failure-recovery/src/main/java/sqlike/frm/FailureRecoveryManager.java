package sqlike.frm;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.checkpoint.CheckpointManager;
import sqlike.frm.checkpoint.CheckpointScheduler;
import sqlike.frm.mock.MockRecoveryLog;
import sqlike.frm.pitr.PointInTimeRecovery;
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
public class FailureRecoveryManager implements IFailureRecoveryManager, AutoCloseable {

    private static final long MAINTENANCE_TIMEOUT_MS = 30_000L;

    private final AtomicLong mockLastLsn = new AtomicLong(0);
    private final CheckpointManager checkpointManager;
    private final CheckpointScheduler checkpointScheduler;
    private final PointInTimeRecovery pointInTimeRecovery;
    private final Lock maintenanceLock = new ReentrantLock();
    private volatile RecoveryState state = RecoveryState.NOT_STARTED;

    // TODO(FR2): private final LogManager logManager;
    // TODO(FR3): private final RollbackManager rollbackManager;
    // TODO(FR5): private final RecoveryManager recoveryManager;

    public FailureRecoveryManager(SmPort sm, CcmPort ccm, String logPath) {
        this(sm, ccm, logPath, Duration.ZERO);
    }

    public FailureRecoveryManager(
            SmPort sm, CcmPort ccm, String logPath, Duration checkpointInterval) {
        MockRecoveryLog recoveryLog = new MockRecoveryLog();
        this.checkpointManager = new CheckpointManager(
                sm, ccm, recoveryLog, MAINTENANCE_TIMEOUT_MS, maintenanceLock);
        this.pointInTimeRecovery = new PointInTimeRecovery(
                sm, ccm, recoveryLog, MAINTENANCE_TIMEOUT_MS, maintenanceLock);
        this.checkpointScheduler = new CheckpointScheduler(
                checkpointInterval, this::scheduledCheckpoint);
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
        maintenanceLock.lock();
        try {
            ensureReady();
            checkpointManager.checkpoint();
        } finally {
            maintenanceLock.unlock();
        }
    }

    @Override
    public void recoverTo(RecoveryTarget target) {
        maintenanceLock.lock();
        try {
            ensureReady();
            state = RecoveryState.RUNNING;
            try {
                pointInTimeRecovery.recoverTo(target);
                state = RecoveryState.READY;
            } catch (RuntimeException error) {
                state = RecoveryState.FAILED;
                throw error;
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    /** Returns the log in LSN order, optionally only one transaction's records (FR-03). */
    public String showLogs(Optional<Long> txId) {
        // MOCK: TODO(FR2): return logManager.showLogs(txId);
        return "";
    }

    public RecoveryState getState() {
        return state;
    }

    public void startCheckpointScheduler() {
        ensureReady();
        checkpointScheduler.start();
    }

    public void stopCheckpointScheduler() {
        checkpointScheduler.stop();
    }

    public boolean isCheckpointSchedulerRunning() {
        return checkpointScheduler.isRunning();
    }

    private void scheduledCheckpoint() {
        maintenanceLock.lock();
        try {
            if (state == RecoveryState.READY) {
                checkpointManager.checkpoint();
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    @Override
    public void close() {
        checkpointScheduler.close();
    }

    /** Rejects requests until startup recovery has finished (FR-07). */
    private void ensureReady() {
        if (state != RecoveryState.READY) {
            throw new IllegalStateException("FRM is not ready: recovery state is " + state);
        }
    }
}
