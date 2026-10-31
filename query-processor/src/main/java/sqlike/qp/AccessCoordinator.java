package sqlike.qp;

import java.util.*;
import sqlike.shared.AccessStatus;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;

public final class AccessCoordinator {
    private final IConcurrencyControl concurrency;

    public AccessCoordinator(IConcurrencyControl concurrency) {
        this.concurrency = Objects.requireNonNull(concurrency);
    }

    public void acquireAll(PreparedStatement statement, ExecutionContext context) {
        if (statement == null || statement.accesses() == null || statement.accesses().isEmpty()) {
            return;
        }

        Map<TableId, AccessMode> mergedByTable = new TreeMap<>(Comparator.comparing(TableId::value));
        Map<TableId, ResourceKey> resourceByTable = new LinkedHashMap<>();

        for (AccessRequest request : statement.accesses()) {
            TableId table = request.resource().tableId();
            mergedByTable.merge(table, request.mode(), (oldMode, newMode) ->
                    (oldMode == AccessMode.WRITE || newMode == AccessMode.WRITE) ? AccessMode.WRITE : AccessMode.READ
            );
            resourceByTable.merge(table, request.resource(), (oldRes, newRes) ->
                    oldRes.rid().isEmpty() ? oldRes : newRes
            );
        }

        for (Map.Entry<TableId, AccessMode> entry : mergedByTable.entrySet()) {
            TableId table = entry.getKey();
            AccessMode mode = entry.getValue();
            ResourceKey resource = resourceByTable.getOrDefault(table, ResourceKey.table(table));
            context.addGrant(acquireOne(resource, mode, context));
        }
    }

    private AccessGrant acquireOne(ResourceKey resource, AccessMode mode, ExecutionContext context) {
        context.check();
        TxId tx = new TxId(context.txId);
        if (concurrency.getState(tx) != TransactionState.ACTIVE) {
            throw failure("Access requires an active transaction");
        }
        AccessGrant grant = awaitDecision(concurrency.acquireAccess(tx, resource, mode), context);
        if (!tx.equals(grant.txId()) || !resource.equals(grant.resource())
                || (mode == AccessMode.WRITE && grant.mode() != AccessMode.WRITE)) {
            throw failure("CCM returned a mismatched grant");
        }
        return grant;
    }

    private AccessGrant awaitDecision(AccessDecision decision, ExecutionContext context) {
        while (decision.status() == AccessStatus.WAIT) {
            context.check();
            if (Thread.currentThread().isInterrupted()) {
                throw failure("Access wait interrupted");
            }
            if (decision.requestId() == null) {
                throw failure("WAIT has no request ID");
            }
            decision = concurrency.waitAccess(decision.requestId());
        }
        if (decision.status() != AccessStatus.GRANTED) {
            throw failure("CCM denied access or aborted transaction");
        }
        context.check();
        return decision.grant().orElseThrow(() -> failure("GRANTED has no access grant"));
    }
}
