package sqlike.frm.maintenance;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.frm.checkpoint.*;
import sqlike.frm.pitr.*;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;

/**
 * Pintu masuk utama (Facade) fitur maintenance basis data.
 * Menggabungkan Checkpoint Manual, Checkpoint Periodik, PITR, dan SnapshotCatalog.
 */
public class MaintenanceService implements AutoCloseable {

    public static final String SNAPSHOT_CATALOG_FILE = "snapshots.catalog";

    private final SmPort storage;
    private final CcmPort concurrency;
    private final CheckpointLogPort checkpointLog;
    private final HistoricalLogPort historicalLog;
    private final CheckpointManager checkpointManager;
    private final PointInTimeRecovery pitr;
    private final SnapshotCatalog catalog;
    private final Duration periodicInterval;
    private final Lock maintenanceGate;
    private final long idleTimeoutMs;

    private ScheduledExecutorService scheduler;
    private PeriodicFailure lastFailure;

    public MaintenanceService(
            SmPort storage,
            CcmPort concurrency,
            CheckpointLogPort checkpointLog,
            HistoricalLogPort historicalLog,
            Path catalogPath,
            Duration periodicInterval,
            long idleTimeoutMs) {
        this(storage, concurrency, checkpointLog, historicalLog, catalogPath, periodicInterval, idleTimeoutMs, new ReentrantLock());
    }

    public MaintenanceService(
            SmPort storage,
            CcmPort concurrency,
            CheckpointLogPort checkpointLog,
            HistoricalLogPort historicalLog,
            Path catalogPath,
            Duration periodicInterval,
            long idleTimeoutMs,
            Lock maintenanceGate) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.concurrency = Objects.requireNonNull(concurrency, "concurrency");
        this.checkpointLog = Objects.requireNonNull(checkpointLog, "checkpointLog");
        this.historicalLog = Objects.requireNonNull(historicalLog, "historicalLog");
        this.periodicInterval = periodicInterval != null ? periodicInterval : Duration.ZERO;
        this.idleTimeoutMs = idleTimeoutMs;
        this.maintenanceGate = maintenanceGate != null ? maintenanceGate : new ReentrantLock();

        this.catalog = new SnapshotCatalog(catalogPath);
        this.checkpointManager = new CheckpointManager(storage, concurrency, checkpointLog, idleTimeoutMs, this.maintenanceGate);
        this.pitr = new PointInTimeRecovery(storage, concurrency, checkpointLog, historicalLog, catalog);
    }

    public CheckpointInfo checkpoint() {
        CheckpointInfo info = checkpointManager.checkpoint();
        catalog.recordSnapshot(new SnapshotEntry(info.snapshotId(), info.flushedThroughLSN(), info.checkpointLSN(), info.createdAt()));
        lastFailure = null;
        return info;
    }

    public HistoricalRecoveryPlan recoverTo(RecoveryTarget target) {
        maintenanceGate.lock();
        try {
            concurrency.pauseNewTransactions();
            try {
                if (!concurrency.waitUntilIdle(idleTimeoutMs)) {
                    throw new IllegalStateException("checkpoint timed out waiting for active transactions");
                }
                return pitr.recoverTo(target);
            } finally {
                concurrency.resumeTransactions();
            }
        } finally {
            maintenanceGate.unlock();
        }
    }

    public synchronized void startPeriodicCheckpoints() {
        if (periodicInterval.isZero() || periodicInterval.isNegative() || isPeriodicCheckpointRunning()) return;

        scheduler = Executors.newSingleThreadScheduledExecutor();
        long millis = periodicInterval.toMillis();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkpoint();
            } catch (Throwable t) {
                lastFailure = new PeriodicFailure(Instant.now(), t.getMessage(), t);
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stopPeriodicCheckpoints() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public synchronized boolean isPeriodicCheckpointRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    public Optional<PeriodicFailure> lastPeriodicFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public Optional<SnapshotEntry> latestRecoveryPoint() {
        return catalog.latestValidSnapshot();
    }

    public Lock maintenanceGate() {
        return maintenanceGate;
    }

    @Override
    public void close() {
        stopPeriodicCheckpoints();
    }
}