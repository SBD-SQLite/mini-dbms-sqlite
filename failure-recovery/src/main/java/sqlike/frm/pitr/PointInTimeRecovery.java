package sqlike.frm.pitr;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;

/** Coordinates historical database recovery while the database is in maintenance mode. */
public class PointInTimeRecovery {

    private final SmPort storageManager;
    private final CcmPort concurrencyControl;
    private final HistoricalLogPort historicalLog;
    private final long idleTimeoutMs;
    private final Lock maintenanceLock;

    public PointInTimeRecovery(
            SmPort storageManager,
            CcmPort concurrencyControl,
            HistoricalLogPort historicalLog,
            long idleTimeoutMs) {
        this(storageManager, concurrencyControl, historicalLog, idleTimeoutMs, new ReentrantLock());
    }

    public PointInTimeRecovery(
            SmPort storageManager,
            CcmPort concurrencyControl,
            HistoricalLogPort historicalLog,
            long idleTimeoutMs,
            Lock maintenanceLock) {
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
        this.concurrencyControl = Objects.requireNonNull(concurrencyControl, "concurrencyControl");
        this.historicalLog = Objects.requireNonNull(historicalLog, "historicalLog");
        this.maintenanceLock = Objects.requireNonNull(maintenanceLock, "maintenanceLock");
        if (idleTimeoutMs <= 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be positive");
        }
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /** Restores a snapshot and replays committed WAL records through the requested target. */
    public void recoverTo(RecoveryTarget target) {
        validateTarget(target);

        maintenanceLock.lock();
        try {
            boolean restoreStarted = false;
            boolean recoveryInitialized = false;
            concurrencyControl.pauseNewTransactions();
            try {
                if (!concurrencyControl.waitUntilIdle(idleTimeoutMs)) {
                    throw new IllegalStateException(
                            "historical recovery timed out waiting for transactions");
                }

                HistoricalRecoveryPlan plan = historicalLog.planFor(target);
                restoreStarted = true;
                storageManager.restoreSnapshot(plan.snapshotId());
                historicalLog.replay(plan, storageManager);
                concurrencyControl.initAfterRecovery(plan.maxTxId());
                recoveryInitialized = true;
            } finally {
                if (!restoreStarted && !recoveryInitialized) {
                    concurrencyControl.resumeTransactions();
                }
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    private static void validateTarget(RecoveryTarget target) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(target.kind(), "target.kind");
        Objects.requireNonNull(target.time(), "target.time");
        Objects.requireNonNull(target.txId(), "target.txId");

        if (target.kind() == RecoveryTargetKind.TIME) {
            if (target.time().isEmpty() || target.txId().isPresent()) {
                throw new IllegalArgumentException("TIME target requires only time");
            }
            return;
        }

        if (target.txId().isEmpty() || target.txId().orElseThrow() <= 0 || target.time().isPresent()) {
            throw new IllegalArgumentException("TRANSACTION target requires only a positive txId");
        }
    }
}
