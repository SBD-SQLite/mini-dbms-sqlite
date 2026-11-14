package sqlike.ccm;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;

/**
 * Transaction data shared by the CCM facade and its protocols.
 *
 * <p>The transaction manager assigns IDs and controls status changes.
 */
public class Transaction {

    private final long txId;
    private final long timestamp;
    private volatile TransactionStatus status;

    private final ReentrantLock applyLock = new ReentrantLock();
    private final Condition applyDone = applyLock.newCondition();
    private final Set<Long> grantedLsns = new HashSet<>();
    private int applying;

    public Transaction(long txId, long timestamp) {
        if (txId <= 0 || timestamp <= 0) {
            throw new IllegalArgumentException("Transaction ID and timestamp must be positive");
        }
        this.txId = txId;
        this.timestamp = timestamp;
        this.status = TransactionStatus.ACTIVE;
    }

    public long getTxId() {
        return txId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    /** Call this after the transaction manager has checked the status change. */
    void setStatus(TransactionStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public boolean isActive() {
        return status == TransactionStatus.ACTIVE;
    }

    public TransactionResponse toResponse() {
        return new TransactionResponse(txId, timestamp, status, Optional.empty());
    }

    /** Remembers an LSN that CCM granted to this transaction. */
    void grantLsn(long lsn) {
        applyLock.lock();
        try {
            grantedLsns.add(lsn);
        } finally {
            applyLock.unlock();
        }
    }

    /** Starts an apply step; refused unless the transaction is ACTIVE and {@code lsn} was granted. */
    boolean beginApply(long lsn) {
        applyLock.lock();
        try {
            if (!isActive() || !grantedLsns.contains(lsn)) {
                return false;
            }
            applying++;
            return true;
        } finally {
            applyLock.unlock();
        }
    }

    void endApply() {
        applyLock.lock();
        try {
            if (applying > 0) {
                applying--;
            }
            applyDone.signalAll();
        } finally {
            applyLock.unlock();
        }
    }

    /** Returns true when no apply step is running, false on timeout or interrupt. */
    boolean awaitNoApply(long timeoutMs) {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        applyLock.lock();
        try {
            while (applying > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = applyDone.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            applyLock.unlock();
        }
    }
}
