package sqlike.frm.maintenance;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.frm.checkpoint.CheckpointInfo;
import sqlike.frm.checkpoint.CheckpointLogPort;
import sqlike.frm.checkpoint.CheckpointManager;
import sqlike.frm.checkpoint.CheckpointScheduler;
import sqlike.frm.checkpoint.SnapshotCatalog;
import sqlike.frm.pitr.HistoricalLogPort;
import sqlike.frm.pitr.HistoricalRecoveryPlan;
import sqlike.frm.pitr.PitrMarker;
import sqlike.frm.pitr.PointInTimeRecovery;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.shared.RecoveryTarget;

/**
 * Database maintenance: manual and periodic checkpoints, and point-in-time recovery.
 *
 * <p>All operations share one maintenance gate and one snapshot catalog, so a checkpoint never
 * overlaps a restore and a restore always sees the snapshots checkpoints created.
 */
public class MaintenanceService implements AutoCloseable {

    public static final String SNAPSHOT_CATALOG_FILE = "snapshots.catalog";
    public static final String PITR_MARKER_FILE = "pitr.inprogress";

    public record PeriodicFailure(Instant at, RuntimeException error) {
    }

    private final Lock maintenanceGate;
    private final SnapshotCatalog snapshotCatalog;
    private final CheckpointManager checkpointManager;
    private final PointInTimeRecovery pointInTimeRecovery;
    private final CheckpointScheduler scheduler;
    private final AtomicReference<PeriodicFailure> lastPeriodicFailure = new AtomicReference<>();

    /**
     * @param catalogPath      snapshot catalog file; {@code null} keeps it in memory
     * @param periodicInterval zero or negative disables periodic checkpoints
     * @param idleTimeoutMs    maximum wait for active transactions
     */
    public MaintenanceService(
            SmPort storage,
            CcmPort concurrency,
            CheckpointLogPort checkpointLog,
            HistoricalLogPort historicalLog,
            Path catalogPath,
            Duration periodicInterval,
            long idleTimeoutMs,
            Lock maintenanceGate) {
        this.maintenanceGate = Objects.requireNonNull(maintenanceGate, "maintenanceGate");
        this.snapshotCatalog = new SnapshotCatalog(catalogPath);
        this.checkpointManager = new CheckpointManager(
                storage, concurrency, checkpointLog, snapshotCatalog, idleTimeoutMs, maintenanceGate);
        this.pointInTimeRecovery = new PointInTimeRecovery(
                storage, concurrency, historicalLog, snapshotCatalog, checkpointManager,
                idleTimeoutMs, maintenanceGate,
                // No catalog file means an in-memory setup, so no marker is needed.
                catalogPath == null ? null : new PitrMarker(catalogPath.resolveSibling(PITR_MARKER_FILE)));
        this.scheduler = new CheckpointScheduler(
                Objects.requireNonNull(periodicInterval, "periodicInterval"), this::periodicCheckpoint);
    }

    public MaintenanceService(
            SmPort storage,
            CcmPort concurrency,
            CheckpointLogPort checkpointLog,
            HistoricalLogPort historicalLog,
            Path catalogPath,
            Duration periodicInterval,
            long idleTimeoutMs) {
        this(storage, concurrency, checkpointLog, historicalLog, catalogPath, periodicInterval,
                idleTimeoutMs, new ReentrantLock());
    }

    public CheckpointInfo checkpoint() {
        return checkpointManager.checkpoint();
    }

    public HistoricalRecoveryPlan recoverTo(RecoveryTarget target) {
        return pointInTimeRecovery.recoverTo(target);
    }

    /** Completes an interrupted point-in-time recovery; call at startup before crash recovery. */
    public Optional<HistoricalRecoveryPlan> resumeInterruptedRecovery() {
        return pointInTimeRecovery.resumeInterrupted();
    }

    /** True while a point-in-time restore is flagged as in progress. */
    public boolean isRecoveryInterrupted() {
        return pointInTimeRecovery.isInterrupted();
    }

    public void startPeriodicCheckpoints() {
        scheduler.start();
    }

    public void stopPeriodicCheckpoints() {
        scheduler.stop();
    }

    public boolean isPeriodicCheckpointRunning() {
        return scheduler.isRunning();
    }

    /** Failure of the latest periodic checkpoint run; empty once a later run succeeds. */
    public Optional<PeriodicFailure> lastPeriodicFailure() {
        return Optional.ofNullable(lastPeriodicFailure.get());
    }

    public Optional<SnapshotCatalog.SnapshotEntry> latestRecoveryPoint() {
        return snapshotCatalog.latestUsableFor(Long.MAX_VALUE);
    }

    public Lock maintenanceGate() {
        return maintenanceGate;
    }

    @Override
    public void close() {
        scheduler.close();
    }

    private void periodicCheckpoint() {
        try {
            checkpointManager.checkpoint();
            lastPeriodicFailure.set(null);
        } catch (RuntimeException error) {
            lastPeriodicFailure.set(new PeriodicFailure(Instant.now(), error));
        }
    }
}
