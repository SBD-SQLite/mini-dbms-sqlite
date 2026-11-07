package sqlike.frm.checkpoint;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;

/** Coordinates a quiescent checkpoint across CCM, Storage Manager, and WAL (FR-06). */
public class CheckpointManager {

    private final SmPort storageManager;
    private final CcmPort concurrencyControl;
    private final CheckpointLogPort checkpointLog;
    private final SnapshotCatalog snapshotCatalog;
    private final long idleTimeoutMs;
    private final Lock maintenanceLock;

    public CheckpointManager(
            SmPort storageManager,
            CcmPort concurrencyControl,
            CheckpointLogPort checkpointLog,
            SnapshotCatalog snapshotCatalog,
            long idleTimeoutMs) {
        this(storageManager, concurrencyControl, checkpointLog, snapshotCatalog, idleTimeoutMs,
                new ReentrantLock());
    }

    public CheckpointManager(
            SmPort storageManager,
            CcmPort concurrencyControl,
            CheckpointLogPort checkpointLog,
            SnapshotCatalog snapshotCatalog,
            long idleTimeoutMs,
            Lock maintenanceLock) {
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
        this.concurrencyControl = Objects.requireNonNull(concurrencyControl, "concurrencyControl");
        this.checkpointLog = Objects.requireNonNull(checkpointLog, "checkpointLog");
        this.snapshotCatalog = Objects.requireNonNull(snapshotCatalog, "snapshotCatalog");
        this.maintenanceLock = Objects.requireNonNull(maintenanceLock, "maintenanceLock");
        if (idleTimeoutMs <= 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be positive");
        }
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /** Creates one completed checkpoint or throws without leaving transactions paused. */
    public CheckpointInfo checkpoint() {
        maintenanceLock.lock();
        try {
            concurrencyControl.pauseNewTransactions();
            try {
                if (!concurrencyControl.waitUntilIdle(idleTimeoutMs)) {
                    throw new IllegalStateException(
                            "checkpoint timed out waiting for active transactions");
                }
                return checkpointWhileQuiesced();
            } finally {
                concurrencyControl.resumeTransactions();
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    /** Checkpoint steps only; the caller must hold the maintenance lock with no active transaction. */
    public CheckpointInfo checkpointWhileQuiesced() {
        long flushedThroughLSN = checkpointLog.getLastLsn();
        checkpointLog.ensureDurable(flushedThroughLSN);
        storageManager.flushAll();
        String snapshotId = storageManager.createSnapshot();
        CheckpointInfo info = checkpointLog.logCheckpoint(flushedThroughLSN, snapshotId);
        checkpointLog.ensureDurable(info.checkpointLSN());
        snapshotCatalog.recordSnapshot(info);
        return info;
    }
}
