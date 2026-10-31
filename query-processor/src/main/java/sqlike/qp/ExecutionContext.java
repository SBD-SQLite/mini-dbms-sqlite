package sqlike.qp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;

/**
 * Execution context carrying transaction ID, session info, grants, and runtime state during query execution.
 */
public final class ExecutionContext {
    public final long txId;
    public final Optional<String> sessionId;
    public final boolean implicit;
    private final AtomicLong sequence;
    private final CancellationToken cancellation;
    private final Map<ResourceKey, AccessGrant> grants = new ConcurrentHashMap<>();

    public ExecutionContext(long txId, Optional<String> sessionId) {
        this(txId, sessionId, false, new AtomicLong(), new CancellationToken());
    }

    public ExecutionContext(long txId, Optional<String> sessionId, boolean implicit, AtomicLong sequence, CancellationToken cancellation) {
        this.txId = txId;
        this.sessionId = sessionId == null ? Optional.empty() : sessionId;
        this.implicit = implicit;
        this.sequence = sequence != null ? sequence : new AtomicLong();
        this.cancellation = cancellation != null ? cancellation : new CancellationToken();
    }

    public ExecutionContext(SessionId session, TxId tx, boolean implicit, AtomicLong sequence, CancellationToken token) {
        this(tx != null ? tx.value() : 0L,
             session != null ? Optional.of(session.value().toString()) : Optional.empty(),
             implicit, sequence, token);
    }

    public static ExecutionContext of(long txId) {
        return new ExecutionContext(txId, Optional.empty());
    }

    public static ExecutionContext of(long txId, String sessionId) {
        return new ExecutionContext(txId, Optional.ofNullable(sessionId));
    }

    public long txId() {
        return txId;
    }

    public Optional<String> sessionId() {
        return sessionId;
    }

    public boolean implicit() {
        return implicit;
    }

    public void check() {
        if (cancellation != null) {
            cancellation.check();
        }
    }

    public ChangeId nextChangeId() {
        return new ChangeId(new TxId(txId), sequence.incrementAndGet());
    }

    public void addGrant(AccessGrant grant) {
        if (grant != null) {
            if (grant.txId().value() != txId) {
                throw failure("Grant belongs to another transaction");
            }
            grants.compute(grant.resource(), (key, old) -> old != null && old.mode() == AccessMode.WRITE ? old : grant);
        }
    }

    public AccessGrant requireGrant(TableId table, AccessMode mode) {
        return requireGrant(table.value(), mode);
    }

    public AccessGrant requireGrant(String tableName, AccessMode mode) {
        check();
        ResourceKey key = ResourceKey.table(new TableId(tableName));
        AccessGrant grant = grants.get(key);
        if (grant == null && grants.isEmpty()) {
            grant = new AccessGrant(new TxId(txId), key, mode);
            grants.put(key, grant);
        }
        if (grant == null || (mode == AccessMode.WRITE && grant.mode() != AccessMode.WRITE)) {
            throw failure("Missing " + mode + " grant for " + tableName);
        }
        return grant;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof ExecutionContext that) {
            return txId == that.txId && Objects.equals(sessionId, that.sessionId) && implicit == that.implicit;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(txId, sessionId, implicit);
    }

    @Override
    public String toString() {
        return "ExecutionContext[txId=" + txId + ", sessionId=" + sessionId + ", implicit=" + implicit + "]";
    }
}

