package sqlike.frm;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.maintenance.MaintenanceService;
import sqlike.frm.maintenance.WalRecoveryLog;
import sqlike.frm.mock.MockRecoveryLog;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;
import sqlike.shared.RecoveryResult;
import sqlike.shared.RecoveryTarget;

/**
 * Single entry point of FRM (FR1). Delegates operations to underlying modules (Maintenance, WAL, Rollback, ARIES).
 */
public class FailureRecoveryManager implements IFailureRecoveryManager, AutoCloseable {

    private static final long MAINTENANCE_TIMEOUT_MS = 30_000L;

    private final AtomicLong mockLastLsn = new AtomicLong(0);
    private final Lock maintenanceLock = new ReentrantLock();
    private final MaintenanceService maintenanceService;

    private volatile RecoveryState state = RecoveryState.NOT_STARTED;

    // TODO(FR2): private final LogManager logManager;
    // TODO(FR3): private final RollbackManager rollbackManager;
    // TODO(FR5): private final RecoveryManager recoveryManager;

    public FailureRecoveryManager(SmPort sm, CcmPort ccm, String logPath) {
        this(sm, ccm, logPath, Duration.ZERO);
    }

    public FailureRecoveryManager(
            SmPort sm, CcmPort ccm, String logPath, Duration checkpointInterval) {
        
        // Menggunakan MockRecoveryLog sementara sampai LogManager (FR2) selesai
        MockRecoveryLog mockLog = new MockRecoveryLog();
        Path catalogPath = Path.of(logPath).resolveSibling(MaintenanceService.SNAPSHOT_CATALOG_FILE);

        // MaintenanceService menyatukan Checkpoint, PITR, Scheduler, dan SnapshotCatalog
        this.maintenanceService = new MaintenanceService(
                sm,
                ccm,
                mockLog,
                mockLog,
                catalogPath,
                checkpointInterval,
                MAINTENANCE_TIMEOUT_MS,
                maintenanceLock
        );
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
        // MOCK: TODO(FR2): logManager.logCommit(txId);
        mockLastLsn.incrementAndGet();
    }

    @Override
    public void logAbort(long txId) {
        ensureReady();
        // MOCK: TODO(FR2): logManager.logAbort(txId);
        mockLastLsn.incrementAndGet();
    }

    @Override
    public void ensureDurable(long lsn) {
        // MOCK: TODO(FR2): logManager.ensureDurable(lsn);
    }

    @Override
    public void rollback(long txId) {
        ensureReady();
        // MOCK: TODO(FR3): rollbackManager.rollback(txId);
    }

    @Override
    public RecoveryResult recover() {
        state = RecoveryState.RUNNING;
        // MOCK: TODO(FR5): RecoveryResult result = recoveryManager.recover();
        RecoveryResult result = new RecoveryResult(0L, 0L, mockLastLsn.get(), Optional.empty());
        state = RecoveryState.READY;
        return result;
    }

    @Override
    public void checkpoint() {
        ensureReady();
        maintenanceService.checkpoint();
    }

    @Override
    public void recoverTo(RecoveryTarget target) {
        ensureReady();
        state = RecoveryState.RUNNING;
        try {
            maintenanceService.recoverTo(target);
            state = RecoveryState.READY;
        } catch (RuntimeException error) {
            state = RecoveryState.FAILED;
            throw error;
        }
    }

    public String showLogs(Optional<Long> txId) {
        // MOCK: TODO(FR2): return logManager.showLogs(txId);
        return "";
    }

    public RecoveryState getState() {
        return state;
    }

    public void startCheckpointScheduler() {
        ensureReady();
        maintenanceService.startPeriodicCheckpoints();
    }

    public void stopCheckpointScheduler() {
        maintenanceService.stopPeriodicCheckpoints();
    }

    public boolean isCheckpointSchedulerRunning() {
        return maintenanceService.isPeriodicCheckpointRunning();
    }

    @Override
    public void close() {
        maintenanceService.close();
    }

    /** Rejects requests until startup recovery has finished (FR-07). */
    private void ensureReady() {
        if (state != RecoveryState.READY) {
            throw new IllegalStateException("FRM is not ready: recovery state is " + state);
        }
    }
}