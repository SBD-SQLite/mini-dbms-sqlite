package sqlike.ccm;

import sqlike.shared.AccessStatus;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;

/** Concurrency protocol used by the CCM facade. */
public interface IConcurrencyProtocol {

    ProtocolType getType();

    /** Sets up protocol state when a transaction begins. */
    void onBegin(Transaction tx);

    /** Checks read permission. Data can be read only after GRANTED. */
    AccessStatus checkRead(Transaction tx, ResourceId resource);

    /** Checks write permission. The CCM facade and FRM bridge handle WAL logging. */
    AccessStatus checkWrite(Transaction tx, ResourceId resource);

    /** Releases protocol state after commit is durable or rollback has finished. */
    void release(Transaction tx);

    /** Clears protocol state before transactions resume after recovery. */
    void reset();
}
