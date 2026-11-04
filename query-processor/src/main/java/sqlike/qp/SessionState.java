package sqlike.qp;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import sqlike.qp.port.QpContracts.SessionId;
import sqlike.qp.port.QpContracts.TxId;
import sqlike.shared.ErrorCode;
import sqlike.shared.Session;

/**
 * Per-session runtime state matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class SessionState {
    private final String id;
    private final AtomicReference<TxId> activeTx;
    private final AtomicBoolean explicitTx;
    private final AtomicReference<SessionStatus> status;
    private final AtomicLong changeSequence;
    private final ReentrantLock executionLock;
    public final AtomicLong sequence;

    public SessionState(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be null or blank in SessionState");
        }
        this.id = id;
        this.activeTx = new AtomicReference<>(null);
        this.explicitTx = new AtomicBoolean(false);
        this.status = new AtomicReference<>(SessionStatus.OPEN);
        this.changeSequence = new AtomicLong();
        this.sequence = this.changeSequence;
        this.executionLock = new ReentrantLock();
    }

    public SessionState(SessionId id) {
        this(id != null && id.value() != null ? id.value().toString() : null);
    }

    public SessionState() {
        this(UUID.randomUUID().toString());
    }

    public String getId() {
        return id;
    }

    public String id() {
        return id;
    }

    public SessionId sessionId() {
        try {
            return new SessionId(UUID.fromString(id));
        } catch (Exception e) {
            return new SessionId(UUID.nameUUIDFromBytes(id.getBytes()));
        }
    }

    public synchronized Optional<Long> getCurrentTxId() {
        TxId tx = activeTx.get();
        return tx == null ? Optional.empty() : Optional.of(tx.value());
    }

    public synchronized Optional<TxId> currentTxId() {
        return Optional.ofNullable(activeTx.get());
    }

    public synchronized boolean isExplicitTransaction() {
        return explicitTx.get();
    }

    public SessionStatus getStatus() {
        return status.get();
    }

    public AtomicLong getChangeSequence() {
        return changeSequence;
    }

    public synchronized void attachTransaction(long txId, boolean explicit) {
        TxId current = activeTx.get();
        if (current != null) {
            throw new QpException(
                    ErrorCode.INVALID_STATE,
                    "Session " + id + " already has active transaction " + current.value());
        }
        this.activeTx.set(new TxId(txId));
        this.explicitTx.set(explicit);
    }

    public synchronized void attachTransaction(TxId txId, boolean explicit) {
        if (txId == null) {
            throw new IllegalArgumentException("TxId cannot be null in attachTransaction");
        }
        attachTransaction(txId.value(), explicit);
    }

    public synchronized void clearTransaction() {
        this.activeTx.set(null);
        this.explicitTx.set(false);
    }

    public void markFailed() {
        status.updateAndGet(current -> current == SessionStatus.CLOSED ? current : SessionStatus.FAILED);
    }

    public void markOpen() {
        status.compareAndSet(SessionStatus.FAILED, SessionStatus.OPEN);
    }

    public void markClosing() {
        status.updateAndGet(current -> current == SessionStatus.CLOSED ? current : SessionStatus.CLOSING);
    }

    public void markClosed() {
        status.set(SessionStatus.CLOSED);
    }

    public void lockExecution() {
        executionLock.lock();
    }

    public void unlockExecution() {
        executionLock.unlock();
    }

    public synchronized Session toSession() {
        return new Session(id, getCurrentTxId());
    }
}
