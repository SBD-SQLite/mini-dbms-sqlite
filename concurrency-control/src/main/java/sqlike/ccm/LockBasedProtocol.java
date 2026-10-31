package sqlike.ccm;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import sqlike.shared.AccessStatus;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;

/**
 * Lock-based protocol for CCM.
 *
 * <p>This milestone 1 mock grants every read and write request with non-null arguments.
 * Locking, waiting, and deadlock handling are not implemented yet.
 *
 * <p>The facade checks transaction status and handles WAL. It calls release only after commit is
 * durable or rollback has finished.
 */
public class LockBasedProtocol implements IConcurrencyProtocol {

    private final Map<ResourceId, LockEntry> lockTable = new HashMap<>();
    private final DeadlockHandler deadlockHandler;

    public LockBasedProtocol() {
        this(new DeadlockHandler());
    }

    public LockBasedProtocol(DeadlockHandler deadlockHandler) {
        this.deadlockHandler = Objects.requireNonNull(deadlockHandler, "deadlockHandler");
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.LOCK_BASED;
    }

    @Override
    public void onBegin(Transaction tx) {
        Objects.requireNonNull(tx, "tx");
        // TODO(CC2): track the locks requested by this transaction.
    }

    @Override
    public AccessStatus checkRead(Transaction tx, ResourceId resource) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(resource, "resource");
        // TODO(CC2): acquire a shared lock, waiting if it conflicts with another transaction.
        return AccessStatus.GRANTED;
    }

    @Override
    public AccessStatus checkWrite(Transaction tx, ResourceId resource) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(resource, "resource");
        // TODO(CC2): acquire or upgrade an exclusive lock and check for deadlocks.
        return AccessStatus.GRANTED;
    }

    @Override
    public void release(Transaction tx) {
        Objects.requireNonNull(tx, "tx");
        // TODO(CC2): release this transaction's locks and wake the next eligible waiters.
    }

    @Override
    public void reset() {
        lockTable.clear();
        deadlockHandler.reset();
    }

    /** Lock holders and pending requests will be stored here when locking is implemented. */
    private static final class LockEntry {
    }
}
