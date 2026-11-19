package sqlike.contract;

import sqlike.shared.AccessResponse;
import sqlike.shared.DataChange;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;
import sqlike.shared.SchemaChange;
import sqlike.shared.TransactionResponse;

/**
 * CCM contract from the SQLike supergroup report (section 2.1) and PRD §3.4, implemented by the CCM group.
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

    /** Requests write protection on a resource before reading before-image, without logging a change (PRD §3.4). */
    default AccessResponse requestWriteAccess(long txId, ResourceId resource) {
        throw new UnsupportedOperationException("Pre-write access protection is not implemented");
    }

    /** Final permission and WAL logging of a schema change through FRM (PRD §3.4). */
    default AccessResponse requestSchemaWrite(long txId, SchemaChange change) {
        throw new UnsupportedOperationException("Schema WAL authorization is not implemented");
    }

    /** Prevents concurrent abort/rollback while an authorized SM mutation is being applied (PRD §3.4). */
    default boolean beginApply(long txId, long changeLSN) {
        throw new UnsupportedOperationException("Apply protection is not implemented");
    }

    /** Marks completion of an SM mutation apply step (PRD §3.4). */
    default void endApply(long txId, long changeLSN) {
        throw new UnsupportedOperationException("Apply protection is not implemented");
    }

    /** Timeout overload for checkpoint idle waiting (PRD §3.4). */
    default boolean awaitNoActiveTransactions(long timeoutMs) {
        awaitNoActiveTransactions();
        return true;
    }
}
