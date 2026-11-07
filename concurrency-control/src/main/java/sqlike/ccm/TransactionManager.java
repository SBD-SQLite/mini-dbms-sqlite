package sqlike.ccm;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.shared.TransactionStatus;

/**
 * Transaction IDs, statuses, and the new-transaction gate used during maintenance (CC1, CC-01).
 *
 * <p>One lock guards the pause flag and the count of unfinished transactions, so waiting never spins.
 * Pause only holds back new transactions; running ones can still finish.
 */
public class TransactionManager {

    private static final Map<TransactionStatus, Set<TransactionStatus>> TRANSITIONS =
            new EnumMap<>(TransactionStatus.class);

    static {
        TRANSITIONS.put(TransactionStatus.ACTIVE,
                EnumSet.of(TransactionStatus.PARTIALLY_COMMITTED, TransactionStatus.FAILED));
        TRANSITIONS.put(TransactionStatus.PARTIALLY_COMMITTED,
                EnumSet.of(TransactionStatus.COMMITTED, TransactionStatus.FAILED));
        TRANSITIONS.put(TransactionStatus.FAILED, EnumSet.of(TransactionStatus.ABORTED));
        TRANSITIONS.put(TransactionStatus.COMMITTED, EnumSet.noneOf(TransactionStatus.class));
        TRANSITIONS.put(TransactionStatus.ABORTED, EnumSet.noneOf(TransactionStatus.class));
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition gateChanged = lock.newCondition();
    private final Condition idle = lock.newCondition();
    private final Map<Long, Transaction> transactions = new ConcurrentHashMap<>();
    private long nextTxId = 1;
    private boolean paused;
    private boolean ready;
    private int unfinished;

    /** Creates an ACTIVE transaction with a unique, increasing txId. Waits while paused. */
    public Transaction create() {
        lock.lock();
        try {
            while (true) {
                if (!ready) {
                    throw new IllegalStateException("CCM is not ready: recovery has not finished");
                }
                if (!paused) {
                    break;
                }
                gateChanged.await();
            }
            long txId = nextTxId++;
            Transaction tx = new Transaction(txId, txId);
            transactions.put(txId, tx);
            unfinished++;
            return tx;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to start a transaction", e);
        } finally {
            lock.unlock();
        }
    }

    public Optional<Transaction> get(long txId) {
        return Optional.ofNullable(transactions.get(txId));
    }

    public void setStatus(long txId, TransactionStatus status) {
        Objects.requireNonNull(status, "status");
        Transaction tx = transactions.get(txId);
        if (tx == null) {
            throw new IllegalArgumentException("Unknown transaction " + txId);
        }
        lock.lock();
        try {
            TransactionStatus current = tx.getStatus();
            if (!TRANSITIONS.get(current).contains(status)) {
                throw new IllegalStateException(
                        "Invalid transition " + current + " -> " + status + " for transaction " + txId);
            }
            tx.setStatus(status);
            if (status == TransactionStatus.COMMITTED || status == TransactionStatus.ABORTED) {
                unfinished--;
                idle.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Returns whether any transaction has not finished yet (neither COMMITTED nor ABORTED). */
    public boolean hasActive() {
        lock.lock();
        try {
            return unfinished > 0;
        } finally {
            lock.unlock();
        }
    }

    /** Drops old transaction state so the next txId is greater than {@code lastTxId} (CC-05). */
    public void resetAfterRecovery(long lastTxId) {
        if (lastTxId < 0) {
            throw new IllegalArgumentException("lastTxId must not be negative");
        }
        lock.lock();
        try {
            transactions.clear();
            nextTxId = lastTxId + 1;
            unfinished = 0;
            ready = true;
            idle.signalAll();
            gateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isReady() {
        lock.lock();
        try {
            return ready;
        } finally {
            lock.unlock();
        }
    }

    /** Stops new transactions until the next {@link #resetAfterRecovery(long)} (fail-stop). */
    public void markNotReady() {
        lock.lock();
        try {
            ready = false;
            gateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Holds back new transactions. Safe to call repeatedly. */
    public void pause() {
        lock.lock();
        try {
            paused = true;
        } finally {
            lock.unlock();
        }
    }

    /** Lets held-back transactions start. */
    public void resume() {
        lock.lock();
        try {
            paused = false;
            gateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Waits without a limit until no unfinished transaction remains. */
    public void awaitNoActive() {
        lock.lock();
        try {
            while (unfinished > 0) {
                idle.awaitUninterruptibly();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Returns true when no unfinished transaction remains, false on timeout or interrupt. */
    public boolean awaitNoActive(long timeoutMs) {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        lock.lock();
        try {
            while (unfinished > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = idle.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }
}
