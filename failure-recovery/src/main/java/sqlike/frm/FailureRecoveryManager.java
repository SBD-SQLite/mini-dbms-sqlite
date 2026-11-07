package sqlike.frm;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.contract.IWalDurability;
import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.log.WalException;
import sqlike.frm.maintenance.MaintenanceService;
import sqlike.frm.maintenance.WalRecoveryLog;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.RollbackPort;
import sqlike.frm.port.SmPort;
import sqlike.frm.recovery.RecoveryManager;
import sqlike.shared.DataChange;
import sqlike.shared.ErrorCode;
import sqlike.shared.RecoveryResult;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;

/**
 * Single entry point of FRM (FR1, PRD v1.0 §3.5). Validates the request, checks the lifecycle
 * state, and forwards it to the module that owns it:
 *
 * <ul>
 *   <li>FR2 {@link LogManager}: logBegin, logChange, logCommit, logAbort, ensureDurable, log view</li>
 *   <li>FR3 {@link RollbackPort}: rollback</li>
 *   <li>FR4 {@link MaintenanceService}: checkpoint, periodic checkpoint, and recoverTo</li>
 *   <li>FR5 {@link RecoveryManager}: recover</li>
 * </ul>
 *
 * <p>Lifecycle (FR-07): {@code NOT_STARTED -> RUNNING -> READY | FAILED}. Client logging,
 * rollback, checkpoint, and PITR are rejected until {@link #recover()} reaches READY.
 * {@link #ensureDurable(long)} is never gated, because SM must flush while recovery runs.
 *
 * <p>Startup ownership (PRD §14): only QP calls {@code CCM.initAfterRecovery(lastTxId)} with the
 * {@link RecoveryResult} returned here. For PITR, FRM calls it through {@link CcmPort}.
 *
 * <p>Errors leave as {@link FrmException} with an {@code ErrorInfo}; nothing is swallowed. A WAL
 * failure that FR2 marks as needing recovery, including a COMMIT whose outcome is unknown, moves
 * FRM to FAILED (fail-stop, PRD §12): CCM must not report success or undo that transaction, and
 * the next startup recovery decides its outcome.
 */
public class FailureRecoveryManager
        implements IFailureRecoveryManager, IWalDurability, LogViewer, AutoCloseable {

    /** Used until FR3's RollbackManager is wired; fails loudly instead of pretending to undo. */
    public static final RollbackPort ROLLBACK_NOT_WIRED = txId -> {
        throw new FrmException(ErrorCode.UNSUPPORTED_OPERATION,
                "rollback is not wired yet: FR3 RollbackManager must implement RollbackPort");
    };

    private final FrmConfig config;
    private final LogManager logManager;
    private final RollbackPort rollbackManager;
    private final RecoveryManager recoveryManager;
    private final MaintenanceService maintenance;
    private final Lock maintenanceLock = new ReentrantLock();
    private final Object abortLock = new Object();

    private volatile RecoveryState state = RecoveryState.NOT_STARTED;
    private volatile RecoveryResult lastRecovery;

    /** Normal configuration: periodic checkpoint every 60 s, idle timeout 30 s (PRD §15). */
    public FailureRecoveryManager(SmPort sm, CcmPort ccm, String logPath) {
        this(sm, ccm, FrmConfig.defaults(Path.of(logPath)));
    }

    public FailureRecoveryManager(SmPort sm, CcmPort ccm, String logPath, Duration checkpointInterval) {
        this(sm, ccm, FrmConfig.defaults(Path.of(logPath)).withCheckpointInterval(checkpointInterval));
    }

    public FailureRecoveryManager(SmPort sm, CcmPort ccm, FrmConfig config) {
        this(sm, ccm, config, ROLLBACK_NOT_WIRED);
    }

    public FailureRecoveryManager(SmPort sm, CcmPort ccm, FrmConfig config, RollbackPort rollback) {
        this(sm, ccm, config, rollback, new LogManager(config.logPath().toString()));
    }

    /** Uses a given FR2 log, e.g. a test double; normal code uses the constructors above. */
    public FailureRecoveryManager(SmPort sm, CcmPort ccm, FrmConfig config, RollbackPort rollback,
            LogManager log) {
        Objects.requireNonNull(sm, "sm");
        Objects.requireNonNull(ccm, "ccm");
        this.config = Objects.requireNonNull(config, "config");
        this.rollbackManager = Objects.requireNonNull(rollback, "rollback");
        this.logManager = Objects.requireNonNull(log, "log");
        this.recoveryManager = new RecoveryManager(logManager, sm);
        WalRecoveryLog wal = new WalRecoveryLog(logManager);
        this.maintenance = new MaintenanceService(
                sm, ccm, wal, wal,
                config.logPath().resolveSibling(MaintenanceService.SNAPSHOT_CATALOG_FILE),
                config.checkpointInterval(),
                config.idleTimeout().toMillis(),
                maintenanceLock);
    }

    // ------------------------------------------------------------------ logging (FR2)

    @Override
    public long logBegin(long txId) {
        requireReady("logBegin");
        requireTxId(txId);
        return logged("logBegin(" + txId + ")", () -> logManager.logBegin(txId));
    }

    @Override
    public long logChange(long txId, DataChange change) {
        requireReady("logChange");
        requireTxId(txId);
        validateChange(change);
        return logged("logChange(" + txId + ")", () -> logManager.logChange(txId, change));
    }

    /** Returns only after COMMIT and every earlier record of the transaction are durable. */
    @Override
    public void logCommit(long txId) {
        requireReady("logCommit");
        requireTxId(txId);
        try {
            logManager.logCommit(txId);
        } catch (WalException error) {
            failStop();
            throw new FrmException(ErrorCode.WAL_FAILURE, "logCommit(" + txId + ") failed"
                    + (error.isCommitUnknown() ? "; commit outcome unknown until recovery" : "")
                    + ": " + error.getMessage(), error, error.isCommitUnknown());
        } catch (RuntimeException error) {
            failStop();
            throw new FrmException(ErrorCode.WAL_FAILURE, "logCommit(" + txId + ") failed; "
                    + "commit outcome unknown until recovery: " + error.getMessage(), error, true);
        }
    }

    /**
     * Writes the final ABORT once and makes it durable (PRD §11: CCM owns this call, after
     * rollback succeeded). A second call for the same transaction writes nothing.
     */
    @Override
    public void logAbort(long txId) {
        requireReady("logAbort");
        requireTxId(txId);
        synchronized (abortLock) {
            if (hasLogRecord(txId, LogType.ABORT)) {
                return;
            }
            logged("logAbort(" + txId + ")", () -> {
                logManager.logAbort(txId);
                logManager.ensureDurable(logManager.getLastLsn());
                return 0L;
            });
        }
    }

    /** Durability gate for SM (IWalDurability); allowed in every state, including recovery. */
    @Override
    public void ensureDurable(long lsn) {
        if (lsn < 0) {
            throw new FrmException(ErrorCode.INVALID_VALUE, "LSN must not be negative: " + lsn);
        }
        if (lsn == 0) {
            return;
        }
        logged("ensureDurable(" + lsn + ")", () -> {
            logManager.ensureDurable(lsn);
            return 0L;
        });
    }

    // ------------------------------------------------------------------ rollback (FR3)

    /** Undoes the transaction; does not write ABORT (CCM calls {@link #logAbort} afterwards). */
    @Override
    public void rollback(long txId) {
        requireReady("rollback");
        requireTxId(txId);
        try {
            rollbackManager.rollback(txId);
        } catch (FrmException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new FrmException(ErrorCode.RECOVERY_FAILURE,
                    "rollback(" + txId + ") failed: " + error.getMessage(), error);
        }
    }

    // ------------------------------------------------------------------ recovery (FR5)

    /**
     * Startup crash recovery. Repeated calls after success return the same result. On failure the
     * state stays FAILED and the error is thrown, so QP must not open the server.
     */
    @Override
    public synchronized RecoveryResult recover() {
        if (state == RecoveryState.READY) {
            return lastRecovery;
        }
        if (state == RecoveryState.RUNNING) {
            throw new FrmException(ErrorCode.INVALID_STATE, "recovery is already running");
        }
        state = RecoveryState.RUNNING;
        RecoveryResult result;
        try {
            result = recoveryManager.recover();
        } catch (Exception error) {
            state = RecoveryState.FAILED;
            throw new FrmException(ErrorCode.RECOVERY_FAILURE,
                    "crash recovery failed: " + error.getMessage(), error);
        }
        if (result == null) {
            state = RecoveryState.FAILED;
            throw new FrmException(ErrorCode.RECOVERY_FAILURE, "crash recovery returned no result");
        }
        // FR5 only reads the active epoch; after PITR the old txIds live in archived epochs,
        // so take FR2's max over every epoch too (txIds must never be reused).
        long lastTxId = Math.max(Math.max(0L, result.lastTxId()), logManager.getMaxTxId());
        // PRD §14: one generator, logical timestamp = txId (not the wall-clock time of the log).
        lastRecovery = new RecoveryResult(lastTxId, lastTxId,
                Math.max(0L, result.lastRecoveredLSN()), result.checkpointLSN());
        try {
            ensureBaselineSnapshot();
        } catch (RuntimeException error) {
            state = RecoveryState.FAILED;
            throw new FrmException(ErrorCode.RECOVERY_FAILURE,
                    "cannot create the baseline snapshot for PITR: " + error.getMessage(), error);
        }
        state = RecoveryState.READY;
        if (!config.checkpointInterval().isZero()) {
            maintenance.startPeriodicCheckpoints();
        }
        return lastRecovery;
    }

    // ------------------------------------------------------------------ maintenance (FR4)

    /** Manual quiescent checkpoint; serialized with the timer and PITR by one maintenance lock. */
    @Override
    public void checkpoint() {
        maintenanceLock.lock();
        try {
            requireReady("checkpoint");
            runCheckpoint();
        } finally {
            maintenanceLock.unlock();
        }
    }

    /** Offline point-in-time recovery to a time or a committed transaction (FR-05). */
    @Override
    public void recoverTo(RecoveryTarget target) {
        validateTarget(target);
        maintenanceLock.lock();
        try {
            requireReady("recoverTo");
            state = RecoveryState.RUNNING;
            try {
                maintenance.recoverTo(target);
                state = RecoveryState.READY;
            } catch (RuntimeException error) {
                failStop();
                if (error instanceof FrmException frm) {
                    throw frm;
                }
                throw new FrmException(ErrorCode.RECOVERY_FAILURE,
                        "point-in-time recovery failed: " + error.getMessage(), error);
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    public void startCheckpointScheduler() {
        requireReady("startCheckpointScheduler");
        maintenance.startPeriodicCheckpoints();
    }

    public void stopCheckpointScheduler() {
        maintenance.stopPeriodicCheckpoints();
    }

    public boolean isCheckpointSchedulerRunning() {
        return maintenance.isPeriodicCheckpointRunning();
    }

    /** Last failure of a periodic checkpoint, if any (the timer itself keeps running). */
    public Optional<RuntimeException> getLastScheduledCheckpointError() {
        return maintenance.lastPeriodicFailure().map(MaintenanceService.PeriodicFailure::error);
    }

    // ------------------------------------------------------------------ log view (FR-03)

    @Override
    public List<LogRecord> readLogs(Optional<Long> txId) {
        Objects.requireNonNull(txId, "txId");
        return List.copyOf(logManager.readLogs(txId.orElse(null)));
    }

    @Override
    public String showLogs(Optional<Long> txId) {
        Objects.requireNonNull(txId, "txId");
        return logManager.showLogs(txId.orElse(null));
    }

    // ------------------------------------------------------------------ state

    public RecoveryState getState() {
        return state;
    }

    public FrmConfig getConfig() {
        return config;
    }

    /** Result of the last successful startup recovery, empty before it. */
    public Optional<RecoveryResult> getLastRecovery() {
        return Optional.ofNullable(lastRecovery);
    }

    @Override
    public void close() {
        try {
            maintenance.close();
        } finally {
            logManager.close(); // releases <wal>.lock so the log can be reopened
        }
    }

    // ------------------------------------------------------------------ internals

    private void runCheckpoint() {
        try {
            maintenance.checkpoint();
        } catch (FrmException error) {
            throw error;
        } catch (IllegalStateException error) {
            throw new FrmException(ErrorCode.INVALID_STATE,
                    "checkpoint cancelled: " + error.getMessage(), error);
        } catch (RuntimeException error) {
            throw new FrmException(ErrorCode.IO_ERROR,
                    "checkpoint failed: " + error.getMessage(), error);
        }
    }

    /**
     * PITR needs a snapshot at or before every target (PRD §15). On the first startup no checkpoint
     * exists yet, so one quiescent checkpoint records the baseline. Later startups reuse the
     * persistent snapshot catalog and skip this.
     */
    private void ensureBaselineSnapshot() {
        if (maintenance.latestRecoveryPoint().isEmpty()) {
            maintenance.checkpoint();
        }
    }

    /** Stops accepting work after a failure that only recovery can resolve. */
    private void failStop() {
        state = RecoveryState.FAILED;
        maintenance.stopPeriodicCheckpoints();
    }

    private boolean hasLogRecord(long txId, LogType type) {
        for (LogRecord record : logManager.readLogs(txId)) {
            if (record.type() == type) {
                return true;
            }
        }
        return false;
    }

    private long logged(String operation, LongSupplier call) {
        try {
            return call.getAsLong();
        } catch (FrmException error) {
            throw error;
        } catch (WalException error) {
            if (error.isRecoveryRequired() || error.isCommitUnknown()) {
                failStop();
            }
            throw new FrmException(ErrorCode.WAL_FAILURE,
                    operation + " failed: " + error.getMessage(), error, error.isCommitUnknown());
        } catch (RuntimeException error) {
            throw new FrmException(ErrorCode.WAL_FAILURE,
                    operation + " failed: " + error.getMessage(), error);
        }
    }

    /** Rejects requests until startup recovery has finished (FR-07). */
    private void requireReady(String operation) {
        RecoveryState current = state;
        if (current != RecoveryState.READY) {
            throw new FrmException(ErrorCode.INVALID_STATE,
                    operation + " rejected: FRM is " + current + ", not READY");
        }
    }

    private static void requireTxId(long txId) {
        if (txId <= 0) {
            throw new FrmException(ErrorCode.INVALID_VALUE, "txId must be positive: " + txId);
        }
    }

    /** PRD §4: INSERT absent->exists, DELETE exists->absent, UPDATE exists->exists. */
    private static void validateChange(DataChange change) {
        if (change == null || change.operation() == null || change.tableName() == null
                || change.tableName().isBlank() || change.rid() == null
                || change.before() == null || change.after() == null) {
            throw new FrmException(ErrorCode.INVALID_VALUE, "incomplete DataChange: " + change);
        }
        boolean before = change.before().exists();
        boolean after = change.after().exists();
        boolean valid = switch (change.operation()) {
            case INSERT -> !before && after;
            case DELETE -> before && !after;
            case UPDATE -> before && after;
        };
        if (!valid) {
            throw new FrmException(ErrorCode.INVALID_VALUE,
                    change.operation() + " has wrong before/after existence: " + change);
        }
        if ((!before && !change.before().values().isEmpty())
                || (!after && !change.after().values().isEmpty())) {
            throw new FrmException(ErrorCode.INVALID_VALUE,
                    "an absent RecordImage must have no values: " + change);
        }
    }

    /** Checked before any state change, so an invalid target leaves the database untouched. */
    private static void validateTarget(RecoveryTarget target) {
        if (target == null || target.kind() == null || target.time() == null
                || target.txId() == null) {
            throw new FrmException(ErrorCode.INVALID_VALUE, "incomplete RecoveryTarget: " + target);
        }
        boolean valid = target.kind() == RecoveryTargetKind.TIME
                ? target.time().isPresent() && target.txId().isEmpty()
                : target.txId().isPresent() && target.txId().get() > 0 && target.time().isEmpty();
        if (!valid) {
            throw new FrmException(ErrorCode.INVALID_VALUE,
                    target.kind() + " target needs exactly its own field: " + target);
        }
    }
}
