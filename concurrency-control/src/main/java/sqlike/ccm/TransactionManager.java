package sqlike.ccm;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import sqlike.shared.TransactionStatus;

/**
 * Transaction IDs, statuses, and the new-transaction gate used during maintenance (CC1, CC-01).
 *
 * <p>Milestone 1 MOCK: IDs, lookup, and status changes work; status-change validation and the
 * maintenance gate are not enforced yet.
 */
public class TransactionManager {

    private final Map<Long, Transaction> transactions = new ConcurrentHashMap<>();
    private long nextTxId = 1;
    private volatile boolean acceptingNew = true;

    /** Creates an ACTIVE transaction with a unique, increasing txId. The timestamp equals the txId. */
    public synchronized Transaction create() {
        // TODO(CC1): reject (or block) new transactions while acceptingNew is false.
        long txId = nextTxId++;
        Transaction tx = new Transaction(txId, txId);
        transactions.put(txId, tx);
        return tx;
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
        // TODO(CC1): allow only valid transitions; COMMITTED and ABORTED are final.
        tx.setStatus(status);
    }

    /** Returns whether any transaction has not finished yet (neither COMMITTED nor ABORTED). */
    public boolean hasActive() {
        for (Transaction tx : transactions.values()) {
            TransactionStatus status = tx.getStatus();
            if (status != TransactionStatus.COMMITTED && status != TransactionStatus.ABORTED) {
                return true;
            }
        }
        return false;
    }

    /** Drops old transaction state so the next txId is greater than {@code lastTxId} (CC-05). */
    public synchronized void resetAfterRecovery(long lastTxId) {
        if (lastTxId < 0) {
            throw new IllegalArgumentException("lastTxId must not be negative");
        }
        transactions.clear();
        nextTxId = lastTxId + 1;
        acceptingNew = true;
    }

    public void pause() {
        // MOCK: TODO(CC1): new transactions must wait until resume().
        acceptingNew = false;
    }

    public void awaitNoActive() {
        // MOCK: returns immediately. TODO(CC1): block until hasActive() is false.
    }

    public void resume() {
        // MOCK: TODO(CC1): wake transactions waiting in create().
        acceptingNew = true;
    }

    public boolean isAcceptingNew() {
        return acceptingNew;
    }
}
