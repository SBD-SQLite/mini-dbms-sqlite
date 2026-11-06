package sqlike.ccm;

import java.util.Objects;
import java.util.Optional;

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
 * <p>Milestone 1 MOCK: every contract method is callable and wired to {@link TransactionManager},
 * the active {@link IConcurrencyProtocol}, {@link FRMBridge}, and {@link ScheduleRecorder}. The
 * decisions themselves come from those components, which are still mocks where marked.
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
        this.frmBridge = new FRMBridge(this.recovery);
        this.recorder = new ScheduleRecorder();
        this.protocol = createProtocol(config.protocol());
    }

    @Override
    public TransactionResponse beginTransaction() {
        Transaction tx = transactions.create();
        // TODO(CC3): if logging BEGIN fails, abort the transaction instead of returning ACTIVE.
        frmBridge.logBegin(tx.getTxId());
        protocol.onBegin(tx);
        return tx.toResponse();
    }

    @Override
    public AccessResponse requestRead(long txId, ResourceId resource) {
        Objects.requireNonNull(resource, "resource");
        Optional<Transaction> found = transactions.get(txId);
        if (found.isEmpty()) {
            return accessError(error(ErrorCode.NOT_FOUND, "Unknown transaction " + txId));
        }
        Transaction tx = found.get();
        if (!tx.isActive()) {
            return accessError(error(ErrorCode.INVALID_STATE, "Transaction " + txId + " is " + tx.getStatus()));
        }

        AccessStatus status = protocol.checkRead(tx, resource);
        return switch (status) {
            case GRANTED -> {
                recorder.record(new ScheduleOperation(txId, resource, false));
                yield new AccessResponse(AccessStatus.GRANTED, Optional.empty(), Optional.empty(), Optional.empty());
            }
            case WAIT -> new AccessResponse(AccessStatus.WAIT, Optional.empty(), Optional.empty(), Optional.empty());
            case ABORTED -> abortedAccess(tx, "Read on " + resource.tableName() + " violates the protocol");
        };
    }

    @Override
    public AccessResponse requestWrite(long txId, DataChange change) {
        Objects.requireNonNull(change, "change");
        Optional<Transaction> found = transactions.get(txId);
        if (found.isEmpty()) {
            return accessError(error(ErrorCode.NOT_FOUND, "Unknown transaction " + txId));
        }
        Transaction tx = found.get();
        if (!tx.isActive()) {
            return accessError(error(ErrorCode.INVALID_STATE, "Transaction " + txId + " is " + tx.getStatus()));
        }

        ResourceId resource = ResourceId.ofRecord(change.tableName(), change.rid());
        AccessStatus status = protocol.checkWrite(tx, resource);
        return switch (status) {
            case GRANTED -> {
                // TODO(CC3): if logging the change fails, the write must not be applied; abort instead.
                long lsn = frmBridge.logWrite(txId, change);
                recorder.record(new ScheduleOperation(txId, resource, true));
                yield new AccessResponse(AccessStatus.GRANTED, Optional.of(lsn), Optional.empty(), Optional.empty());
            }
            case WAIT -> new AccessResponse(AccessStatus.WAIT, Optional.empty(), Optional.empty(), Optional.empty());
            case ABORTED -> abortedAccess(tx, "Write on " + change.tableName() + " violates the protocol");
        };
    }

    @Override
    public TransactionResponse commit(long txId) {
        Optional<Transaction> found = transactions.get(txId);
        if (found.isEmpty()) {
            return unknownTransaction(txId);
        }
        Transaction tx = found.get();
        if (!tx.isActive()) {
            return withError(tx, error(ErrorCode.INVALID_STATE, "Transaction " + txId + " is " + tx.getStatus()));
        }

        transactions.setStatus(txId, TransactionStatus.PARTIALLY_COMMITTED);
        // TODO(CC3): if COMMIT is not durable, the transaction must not become COMMITTED; abort instead.
        frmBridge.logCommit(txId);
        transactions.setStatus(txId, TransactionStatus.COMMITTED);
        protocol.release(tx);
        recorder.markCommitted(txId);
        return tx.toResponse();
    }

    @Override
    public TransactionResponse abort(long txId) {
        Optional<Transaction> found = transactions.get(txId);
        if (found.isEmpty()) {
            return unknownTransaction(txId);
        }
        Transaction tx = found.get();
        TransactionStatus status = tx.getStatus();
        if (status == TransactionStatus.COMMITTED || status == TransactionStatus.ABORTED) {
            return withError(tx, error(ErrorCode.INVALID_STATE, "Transaction " + txId + " is " + status));
        }
        return handleAbort(tx, null);
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
     * Single abort path for user ABORT, deadlock victims, and protocol violations (CC-04).
     * Protocol state is kept until rollback has finished.
     */
    private TransactionResponse handleAbort(Transaction tx, ErrorInfo reason) {
        long txId = tx.getTxId();
        transactions.setStatus(txId, TransactionStatus.FAILED);
        // TODO(CC3): if rollback fails, keep the transaction FAILED and keep its protocol state.
        frmBridge.rollbackThenLogAbort(txId);
        transactions.setStatus(txId, TransactionStatus.ABORTED);
        protocol.release(tx);
        return reason == null ? tx.toResponse() : withError(tx, reason);
    }

    private AccessResponse abortedAccess(Transaction tx, String message) {
        ErrorInfo reason = error(ErrorCode.TRANSACTION_ABORTED, message);
        handleAbort(tx, reason);
        return new AccessResponse(AccessStatus.ABORTED, Optional.empty(), Optional.empty(), Optional.of(reason));
    }

    private static IConcurrencyProtocol createProtocol(ProtocolType type) {
        return switch (type) {
            case LOCK_BASED -> new LockBasedProtocol(new DeadlockHandler());
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
