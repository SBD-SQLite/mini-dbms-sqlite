package sqlike.ccm;

import java.util.Objects;
import java.util.Optional;

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
}
