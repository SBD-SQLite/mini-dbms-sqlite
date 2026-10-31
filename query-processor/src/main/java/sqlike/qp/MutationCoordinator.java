package sqlike.qp;

import java.util.*;
import sqlike.shared.*;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;

public final class MutationCoordinator {
    private final IStorageManager storage;
    private final IConcurrencyControl concurrency;
    public MutationCoordinator(IStorageManager storage, IConcurrencyControl concurrency) {
        this.storage = Objects.requireNonNull(storage); this.concurrency = Objects.requireNonNull(concurrency);
    }
    public Rid insert(TableId tableId, Row row, ExecutionContext context) {
        Row values = storage.getSchema(tableId).validate(row);
        Rid rid = storage.reserveInsert(tableId, values, context, context.requireGrant(tableId, AccessMode.WRITE));
        try {
            apply(new ChangeRecord(context.nextChangeId(), context.txId, new DataChange(ChangeOperation.INSERT,
                    tableId.value(), rid.value(), new RecordImage(false, List.of()), new RecordImage(true, values.values()))), context);
        } catch (RuntimeException failure) {
            try { 
                cleanupReservation(tableId, rid, context.txId);
            } 
            catch (RuntimeException cleanup) { 
                failure.addSuppressed(cleanup); 
            }
            throw failure;
        }
        cleanupReservation(tableId, rid, context.txId); return rid;
    }
    public boolean update(TableId tableId, StoredRecord before, Row after, ExecutionContext context) {
        context.requireGrant(tableId, AccessMode.WRITE);
        if (!tableId.equals(before.tableId())) throw failure("Wrong mutation target table");
        Row values = storage.getSchema(tableId).validate(after);
        if (before.row().equals(values)) return false;
        apply(new ChangeRecord(context.nextChangeId(), context.txId, new DataChange(ChangeOperation.UPDATE,
                tableId.value(), before.rid().value(), new RecordImage(true, before.row().values()), new RecordImage(true, values.values()))), context);
        return true;
    }
    public void delete(TableId tableId, StoredRecord before, ExecutionContext context) {
        context.requireGrant(tableId, AccessMode.WRITE);
        if (!tableId.equals(before.tableId())) throw failure("Wrong mutation target table");
        apply(new ChangeRecord(context.nextChangeId(), context.txId, new DataChange(ChangeOperation.DELETE,
                tableId.value(), before.rid().value(), new RecordImage(true, before.row().values()), new RecordImage(false, List.of()))), context);
    }
    private void apply(ChangeRecord change, ExecutionContext context) {
        context.check();
        if (concurrency.getState(context.txId) != TransactionState.ACTIVE) throw failure("Write requires an active transaction");
        WritePermit permit = concurrency.authorizeChange(change);
        if (!change.id().equals(permit.changeId())) throw failure("Mismatched write permit");
        try (WriteLease lease = concurrency.acquireApplyLease(permit)) {
            Objects.requireNonNull(lease); context.check(); storage.applyChange(change, permit); 
            concurrency.confirmApplied(change.id());
        }
    }
    private void cleanupReservation(TableId tableId, Rid rid, TxId txId) { 
        storage.releaseReservation(tableId, rid, txId); 
    }
}
