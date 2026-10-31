package sqlike.contract;

import sqlike.shared.AccessResponse;
import sqlike.shared.DataChange;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionResponse;

/**
 * CCM contract from the SQLike supergroup report (section 2.1), implemented by the CCM group.
 */
public interface IConcurrencyControlManager {

    /** Starts a new transaction and returns its assigned txId, timestamp, and status (CC-01). */
    TransactionResponse beginTransaction();

    /** Requests permission to read a resource on behalf of {@code txId} (CC-06, CC-08). */
    AccessResponse requestRead(long txId, ResourceId resource);

    /** Requests permission to write a record change and logs it via WAL when granted (CC-03, CC-06, CC-08). */
    AccessResponse requestWrite(long txId, DataChange change);

    /** Commits the transaction after its COMMIT log is durable and releases its protocol state (CC-01, CC-03). */
    TransactionResponse commit(long txId);

    /** Aborts the transaction, rolls back its changes via FRM, and releases its protocol state (CC-01, CC-04). */
    TransactionResponse abort(long txId);

    /** Switches the active concurrency control protocol when no transactions are active (CC-02). */
    void setProtocol(ProtocolType protocol);

    /** Initializes CCM state after FRM finishes startup crash recovery (CC-05). */
    void initAfterRecovery(long lastTxId);

    /** Blocks new transactions from starting during maintenance/checkpoint (CC-01). */
    void pauseNewTransactions();

    /** Waits until all currently active transactions have finished (CC-01). */
    void awaitNoActiveTransactions();

    /** Resumes accepting new transactions after maintenance/checkpoint finishes (CC-01). */
    void resumeTransactions();
}
