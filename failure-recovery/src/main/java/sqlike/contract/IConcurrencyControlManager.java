package sqlike.contract;

import sqlike.shared.AccessResponse;
import sqlike.shared.DataChange;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionResponse;

/**
 * Copy of the CCM contract so the FRM adapter compiles without the CCM module.
 *
 * <p>Identical to {@code concurrency-control/.../IConcurrencyControlManager}. Delete this copy once
 * the root {@code contracts/} source set exists (PRD v1.0 §2).
 */
public interface IConcurrencyControlManager {

    TransactionResponse beginTransaction();

    AccessResponse requestRead(long txId, ResourceId resource);

    AccessResponse requestWrite(long txId, DataChange change);

    TransactionResponse commit(long txId);

    TransactionResponse abort(long txId);

    void setProtocol(ProtocolType protocol);

    void initAfterRecovery(long lastTxId);

    void pauseNewTransactions();

    void awaitNoActiveTransactions();

    void resumeTransactions();
}
