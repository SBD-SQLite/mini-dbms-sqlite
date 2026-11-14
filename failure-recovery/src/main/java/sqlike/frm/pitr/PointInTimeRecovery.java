package sqlike.frm.pitr;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.frm.checkpoint.CheckpointManager;
import sqlike.frm.checkpoint.SnapshotCatalog;
import sqlike.frm.log.LogRecord;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;

/**
 * Restores the database to a point in time or to a committed transaction (FR-05). A failure
 * after the restore started keeps new transactions paused because the data may be incomplete.
 */
public class PointInTimeRecovery {

    private final SmPort storageManager;
    private final CcmPort concurrencyControl;
    private final HistoricalLogPort historicalLog;
    private final SnapshotCatalog snapshotCatalog;
    private final CheckpointManager checkpointManager;
    private final HistoricalRecoveryPlanner planner;
    private final long idleTimeoutMs;
    private final Lock maintenanceLock;
    private final Optional<PitrMarker> marker;

    public PointInTimeRecovery(
            SmPort storageManager,
            CcmPort concurrencyControl,
            HistoricalLogPort historicalLog,
            SnapshotCatalog snapshotCatalog,
            CheckpointManager checkpointManager,
            long idleTimeoutMs) {
        this(storageManager, concurrencyControl, historicalLog, snapshotCatalog, checkpointManager,
                idleTimeoutMs, new ReentrantLock());
    }

    public PointInTimeRecovery(
            SmPort storageManager,
            CcmPort concurrencyControl,
            HistoricalLogPort historicalLog,
            SnapshotCatalog snapshotCatalog,
            CheckpointManager checkpointManager,
            long idleTimeoutMs,
            Lock maintenanceLock) {
        this(storageManager, concurrencyControl, historicalLog, snapshotCatalog, checkpointManager,
                idleTimeoutMs, maintenanceLock, null);
    }

    /** A null {@code marker} disables detection of an interrupted restore. */
    public PointInTimeRecovery(
            SmPort storageManager,
            CcmPort concurrencyControl,
            HistoricalLogPort historicalLog,
            SnapshotCatalog snapshotCatalog,
            CheckpointManager checkpointManager,
            long idleTimeoutMs,
            Lock maintenanceLock,
            PitrMarker marker) {
        this.marker = Optional.ofNullable(marker);
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
        this.concurrencyControl = Objects.requireNonNull(concurrencyControl, "concurrencyControl");
        this.historicalLog = Objects.requireNonNull(historicalLog, "historicalLog");
        this.snapshotCatalog = Objects.requireNonNull(snapshotCatalog, "snapshotCatalog");
        this.checkpointManager = Objects.requireNonNull(checkpointManager, "checkpointManager");
        this.maintenanceLock = Objects.requireNonNull(maintenanceLock, "maintenanceLock");
        this.planner = new HistoricalRecoveryPlanner(snapshotCatalog);
        if (idleTimeoutMs <= 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be positive");
        }
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public HistoricalRecoveryPlan recoverTo(RecoveryTarget target) {
        validateTarget(target);

        maintenanceLock.lock();
        try {
            boolean dataTouched = false;
            concurrencyControl.pauseNewTransactions();
            try {
                if (!concurrencyControl.waitUntilIdle(idleTimeoutMs)) {
                    throw new IllegalStateException(
                            "historical recovery timed out waiting for transactions");
                }

                List<LogRecord> history = historicalLog.readHistory();
                HistoricalRecoveryPlan plan = planner.plan(target, history);

                marker.ifPresent(flag -> flag.write(target));
                dataTouched = true;
                restore(plan, history);
                marker.ifPresent(PitrMarker::clear);

                concurrencyControl.initAfterRecovery(plan.maxTxId());
                concurrencyControl.resumeTransactions();
                return plan;
            } catch (RuntimeException error) {
                if (!dataTouched) {
                    concurrencyControl.resumeTransactions();
                }
                throw error;
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    /** Runs an interrupted restore again and returns its plan, or empty when none was interrupted. */
    public Optional<HistoricalRecoveryPlan> resumeInterrupted() {
        if (marker.isEmpty()) {
            return Optional.empty();
        }
        Optional<RecoveryTarget> target = marker.get().read();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        maintenanceLock.lock();
        try {
            List<LogRecord> history = historicalLog.readHistory();
            HistoricalRecoveryPlan plan = planner.plan(target.get(), history);
            restore(plan, history);
            marker.get().clear();
            return Optional.of(plan);
        } finally {
            maintenanceLock.unlock();
        }
    }

    /** True while a restore is flagged as in progress. */
    public boolean isInterrupted() {
        return marker.isPresent() && marker.get().read().isPresent();
    }

    /** Replaces the data, separates the timeline, and checkpoints the restored state. */
    private void restore(HistoricalRecoveryPlan plan, List<LogRecord> history) {
        long lastLsn = history.isEmpty() ? 0L : history.get(history.size() - 1).lsn();

        storageManager.restoreSnapshot(plan.snapshotId());
        replay(plan);
        storageManager.flushAll();

        historicalLog.separateHistory(plan.snapshotId(), plan.targetLSN());
        if (lastLsn > plan.targetLSN()) {
            snapshotCatalog.recordAbandoned(plan.targetLSN() + 1, lastLsn);
        }
        checkpointManager.checkpointWhileQuiesced();
    }

    private void replay(HistoricalRecoveryPlan plan) {
        for (LogRecord record : plan.changesToReplay()) {
            DataChange change = record.change().orElseThrow();
            storageManager.writeAt(change.tableName(), change.rid(), change.after(), record.lsn());
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
