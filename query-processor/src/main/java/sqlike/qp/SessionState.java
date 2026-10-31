package sqlike.qp;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import sqlike.shared.ErrorCode;
import sqlike.shared.Session;


public final class SessionState {
    private final String id;
    private final AtomicReference<SessionStatus> status;
    private final ReentrantLock executionLock;
    private Long activeTxId;
    private boolean explicitTransaction;

    public SessionState(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be null or blank in SessionState");
        }
        this.id = id;
        this.status = new AtomicReference<>(SessionStatus.OPEN);
        this.executionLock = new ReentrantLock();
    }

    public String getId() {
        return id;
    }

    public synchronized Optional<Long> getCurrentTxId() {
        return Optional.ofNullable(activeTxId);
    }

    public synchronized boolean isExplicitTransaction() {
        return explicitTransaction;
    }

    public SessionStatus getStatus() {
        return status.get();
    }

    public synchronized void attachTransaction(long txId, boolean explicit) {
        if (activeTxId != null) {
            throw new QpException(
                    ErrorCode.INVALID_STATE,
                    "Session " + id + " already has active transaction " + activeTxId);
        }
        this.activeTxId = txId;
        this.explicitTransaction = explicit;
    }

    public synchronized void clearTransaction() {
        this.activeTxId = null;
        this.explicitTransaction = false;
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
        return new Session(id, Optional.ofNullable(activeTxId));
    }
}
