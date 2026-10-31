package sqlike.qp;

import java.util.*;
import sqlike.shared.*;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;

public class MutationCoordinator {
    private final IStorageManager storage;
    private final IConcurrencyControl concurrency;

    // Konstruktor untuk kompatibilitas develop/qp (QP2)
    public MutationCoordinator(IStorageManager storage) {
        this(storage, null);
    }

    // Konstruktor utama untuk transaksi tulis QP5
    public MutationCoordinator(IStorageManager storage, IConcurrencyControl concurrency) {
        this.storage = storage;
        this.concurrency = concurrency;
    }

    public IStorageManager getStorage() {
        return storage;
    }

    public Rid insert(TableId tableId, Row row, ExecutionContext context) {
        if (storage == null) throw failure("Storage manager is not initialized in MutationCoordinator");
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
        cleanupReservation(tableId, rid, context.txId);
        return rid;
    }

    public boolean update(TableId tableId, StoredRecord before, Row after, ExecutionContext context) {
        if (storage == null) throw failure("Storage manager is not initialized in MutationCoordinator");
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

    // Overload delete untuk kompatibilitas nama tabel string dari develop/qp
    public void delete(String tableName, StoredRecord before, ExecutionContext context) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name cannot be null or blank in MutationCoordinator.delete");
        }
        delete(new TableId(tableName), before, context);
    }

    private void apply(ChangeRecord change, ExecutionContext context) {
        context.check();
        if (concurrency != null) {
            if (concurrency.getState(context.txId) != TransactionState.ACTIVE) {
                throw failure("Write requires an active transaction");
            }
            WritePermit permit = concurrency.authorizeChange(change);
            if (!change.id().equals(permit.changeId())) {
                throw failure("Mismatched write permit");
            }
            try (WriteLease lease = concurrency.acquireApplyLease(permit)) {
                Objects.requireNonNull(lease);
                context.check();
                if (storage != null) {
                    storage.applyChange(change, permit);
                }
                concurrency.confirmApplied(change.id());
            }
        } else if (storage != null) {
            storage.applyChange(change, new WritePermit(change.id(), new Lsn(0)));
        }
    }

    private void cleanupReservation(TableId tableId, Rid rid, TxId txId) { 
        if (storage != null) {
            storage.releaseReservation(tableId, rid, txId);
        }
    }
}
