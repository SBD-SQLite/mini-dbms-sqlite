package sqlike.ccm;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import sqlike.contract.IConcurrencyControlManager;
import sqlike.contract.IFailureRecoveryManager;
import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ComponentType;
import sqlike.shared.DataChange;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;

/**
 * CCM facade called by QP and FRM (CC1).
 *
 * <p>Connects transactions, the active protocol, the FRM bridge, and the schedule recorder.
 * Lifecycle and WAL work that is still pending is marked with TODO comments.
 */
public class ConcurrencyControlManager implements IConcurrencyControlManager {

    private final IFailureRecoveryManager recovery;
    private final TransactionManager transactions;
    private volatile IConcurrencyProtocol protocol;
    private final FRMBridge frmBridge;
    private final ScheduleRecorder recorder;
    private final CCMConfig config;

    public ConcurrencyControlManager(IFailureRecoveryManager recovery) {
        this(recovery, CCMConfig.defaults());
    }

    public ConcurrencyControlManager(IFailureRecoveryManager recovery, CCMConfig config) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.config = Objects.requireNonNull(config, "config");
        this.transactions = new TransactionManager();
        this.frmBridge = new FRMBridge(this.recovery, config);
        this.recorder = new ScheduleRecorder();
        this.protocol = createProtocol(config.protocol());
    }

    @Override
    public TransactionResponse beginTransaction() {
        Transaction tx;
        try {
            tx = transactions.create();
        } catch (IllegalStateException e) {
            return new TransactionResponse(0L, 0L, TransactionStatus.FAILED,
                    Optional.of(error(ErrorCode.INVALID_STATE, e.getMessage())));
        }
        try {
            frmBridge.logBegin(tx.getTxId());
        } catch (FrmFailureException e) {
            transactions.setStatus(tx.getTxId(), TransactionStatus.FAILED);
            transactions.setStatus(tx.getTxId(), TransactionStatus.ABORTED);
            return withError(tx, error(ErrorCode.WAL_FAILURE, e.getMessage()));
        }
        protocol.onBegin(tx);
        return tx.toResponse();
    }

    @Override
    public AccessResponse requestRead(long txId, ResourceId resource) {
        Objects.requireNonNull(resource, "resource");
        return withActiveTransaction(txId, tx -> switch (protocol.checkRead(tx, resource)) {
            case GRANTED -> {
                recorder.record(new ScheduleOperation(txId, resource, false));
                yield granted(Optional.empty());
            }
            case WAIT -> waiting();
            case ABORTED -> abortedAccess(tx, ErrorCode.TRANSACTION_ABORTED,
                    "Read on " + resource.tableName() + " violates the protocol");
        });
    }

    @Override
    public AccessResponse requestWrite(long txId, DataChange change) {
        Objects.requireNonNull(change, "change");
        return withActiveTransaction(txId, tx -> {
            ResourceId resource = ResourceId.ofRecord(change.tableName(), change.rid());
            return switch (protocol.checkWrite(tx, resource)) {
                case GRANTED -> {
                    long lsn;
                    try {
                        lsn = frmBridge.logWrite(txId, change);
                    } catch (FrmFailureException e) {
                        yield abortedAccess(tx, ErrorCode.WAL_FAILURE, e.getMessage());
                    }
                    tx.grantLsn(lsn);
                    recorder.record(new ScheduleOperation(txId, resource, true));
                    yield granted(Optional.of(lsn));
                }
                case WAIT -> waiting();
                case ABORTED -> abortedAccess(tx, ErrorCode.TRANSACTION_ABORTED,
                        "Write on " + change.tableName() + " violates the protocol");
            };
        });
    }

    @Override
    public AccessResponse requestWriteAccess(long txId, ResourceId resource) {
        Objects.requireNonNull(resource, "resource");
        return withActiveTransaction(txId, tx -> switch (protocol.checkWrite(tx, resource)) {
            case GRANTED -> granted(Optional.empty());
            case WAIT -> waiting();
            case ABORTED -> abortedAccess(tx, ErrorCode.TRANSACTION_ABORTED,
                    "Write access on " + resource.tableName() + " violates the protocol");
        });
    }

    @Override
    public TransactionResponse commit(long txId) {
        Optional<Transaction> found = transactions.get(txId);
        if (found.isEmpty()) {
            return unknownTransaction(txId);
        }
        Transaction tx = found.get();
        synchronized (tx) {
            if (!tx.isActive()) {
                return withError(tx, inactiveError(tx));
            }

            transactions.setStatus(txId, TransactionStatus.PARTIALLY_COMMITTED);
            try {
                frmBridge.logCommit(txId);
            } catch (FrmFailureException e) {
                // COMMIT may already be durable, so do not undo anything: stay PARTIALLY_COMMITTED,
                // keep the locks, and stop accepting transactions until recovery decides.
                transactions.markNotReady();
                return withError(tx, error(ErrorCode.WAL_FAILURE,
                        "Commit outcome of transaction " + txId + " is unknown: " + e.getMessage()));
            }
            transactions.setStatus(txId, TransactionStatus.COMMITTED);
            protocol.release(tx);
            recorder.markCommitted(txId);
            return tx.toResponse();
        }
    }

    @Override
    public TransactionResponse abort(long txId) {
        Optional<Transaction> found = transactions.get(txId);
        if (found.isEmpty()) {
            return unknownTransaction(txId);
        }
        return handleAbort(found.get(), null);
    }

    @Override
    public void setProtocol(ProtocolType protocol) {
        Objects.requireNonNull(protocol, "protocol");
        if (transactions.hasActive()) {
            throw new IllegalStateException("Cannot change protocol while transactions are active");
        }
        // TODO(CC1): block new transactions while the protocol is being replaced.
        if (this.protocol.getType() != protocol) {
            this.protocol = createProtocol(protocol);
        }
    }

    @Override
    public void initAfterRecovery(long lastTxId) {
        transactions.resetAfterRecovery(lastTxId);
        protocol.reset();
        recorder.reset();
    }

    @Override
    public void pauseNewTransactions() {
        transactions.pause();
    }

    @Override
    public void awaitNoActiveTransactions() {
        transactions.awaitNoActive();
    }

    @Override
    public void resumeTransactions() {
        transactions.resume();
    }

    public ProtocolType getActiveProtocol() {
        return protocol.getType();
    }

    public CCMConfig getConfig() {
        return config;
    }

    /** Operations granted by this CCM, used for serializability checks (CC-09). */
    public ScheduleRecorder getRecorder() {
        return recorder;
    }

    /**
     * Single abort path for user ABORT, deadlock victims, protocol violations, and WAL failures (CC-04).
     * Protocol state is kept until rollback and logAbort have finished; a failure leaves the
     * transaction FAILED so that a later abort can retry.
     */
    private TransactionResponse handleAbort(Transaction tx, ErrorInfo reason) {
        // A deadlock callback and a waiting request can reach this method at the same time.
        synchronized (tx) {
            long txId = tx.getTxId();
            TransactionStatus status = tx.getStatus();
            if (status == TransactionStatus.COMMITTED || status == TransactionStatus.ABORTED) {
                return withError(tx, error(ErrorCode.INVALID_STATE, "Transaction " + txId + " is " + status));
            }
            if (status == TransactionStatus.PARTIALLY_COMMITTED) {
                return withError(tx, error(ErrorCode.INVALID_STATE,
                        "Transaction " + txId + " has an unknown commit outcome; recovery is required"));
            }
            if (status == TransactionStatus.ACTIVE) {
                transactions.setStatus(txId, TransactionStatus.FAILED);
            }
            try {
                frmBridge.rollbackThenLogAbort(txId);
            } catch (FrmFailureException e) {
                return withError(tx, error(ErrorCode.WAL_FAILURE, "Transaction " + txId
                        + " stays FAILED with its locks held; abort again to retry: " + e.getMessage()));
            }
            transactions.setStatus(txId, TransactionStatus.ABORTED);
            protocol.release(tx);
            return reason == null ? tx.toResponse() : withError(tx, reason);
        }
    }

    private AccessResponse abortedAccess(Transaction tx, ErrorCode code, String message) {
        ErrorInfo reason = error(code, message);
        handleAbort(tx, reason);
        return new AccessResponse(AccessStatus.ABORTED, Optional.empty(), Optional.empty(), Optional.of(reason));
    }

    private AccessResponse withActiveTransaction(long txId, Function<Transaction, AccessResponse> action) {
        Optional<Transaction> found = transactions.get(txId);
        if (found.isEmpty()) {
            return accessError(error(ErrorCode.NOT_FOUND, "Unknown transaction " + txId));
        }
        Transaction tx = found.get();
        if (!tx.isActive()) {
            return accessError(inactiveError(tx));
        }
        return action.apply(tx);
    }

    private static ErrorInfo inactiveError(Transaction tx) {
        TransactionStatus status = tx.getStatus();
        if (status == TransactionStatus.FAILED || status == TransactionStatus.ABORTED) {
            return error(ErrorCode.TRANSACTION_ABORTED,
                    "Transaction " + tx.getTxId() + " has been aborted (status " + status + ")");
        }
        return error(ErrorCode.INVALID_STATE, "Transaction " + tx.getTxId() + " is " + status);
    }

    private static AccessResponse granted(Optional<Long> lsn) {
        return new AccessResponse(AccessStatus.GRANTED, lsn, Optional.empty(), Optional.empty());
    }

    private static AccessResponse waiting() {
        return new AccessResponse(AccessStatus.WAIT, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private void abortDeadlockVictim(long txId) {
        TransactionResponse response = abort(txId);
        if (response.status() != TransactionStatus.ABORTED
                && response.status() != TransactionStatus.COMMITTED) {
            throw new IllegalStateException("Deadlock victim could not finish abort: " + txId);
        }
    }

    private IConcurrencyProtocol createProtocol(ProtocolType type) {
        return switch (type) {
            case LOCK_BASED -> new LockBasedProtocol(new DeadlockHandler(), this::abortDeadlockVictim);
            case TIMESTAMP_BASED -> new TimestampProtocol();
        };
    }

    private static ErrorInfo error(ErrorCode code, String message) {
        return new ErrorInfo(code, ComponentType.CCM, message, Optional.empty());
    }

    private static AccessResponse accessError(ErrorInfo error) {
        return new AccessResponse(AccessStatus.ABORTED, Optional.empty(), Optional.empty(), Optional.of(error));
    }

    private static TransactionResponse withError(Transaction tx, ErrorInfo error) {
        return new TransactionResponse(tx.getTxId(), tx.getTimestamp(), tx.getStatus(), Optional.of(error));
    }

    private static TransactionResponse unknownTransaction(long txId) {
        return new TransactionResponse(txId, 0L, TransactionStatus.FAILED,
                Optional.of(error(ErrorCode.NOT_FOUND, "Unknown transaction " + txId)));
    }
}
