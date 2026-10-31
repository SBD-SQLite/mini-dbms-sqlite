package sqlike.qp.port;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import sqlike.shared.InsertReservation;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;

/** Adapts the QP storage port to the existing sqlike.contract.IStorageManager. */
public final class StorageAdapter implements IStorageManager {
    /** Optional fixture context; real SM uses its agreed locking/WAL contract instead. */
    public interface TransactionScope { void useTransaction(TxId tx); }
    private record Reservation(TxId tx, InsertReservation value) { }
    private final sqlike.contract.IStorageManager storage;
    private final Map<Rid, Reservation> reservations = new ConcurrentHashMap<>();
    public StorageAdapter(sqlike.contract.IStorageManager storage) { this.storage = Objects.requireNonNull(storage); }
    private void scope(TxId tx) { if (storage instanceof TransactionScope scoped) scoped.useTransaction(tx); }
    private void access(TableId table, ExecutionContext context, AccessGrant grant, AccessMode mode) {
        context.requireGrant(table, mode);
        if (!context.txId.equals(grant.txId()) || !ResourceKey.table(table).equals(grant.resource())
                || (mode == AccessMode.WRITE && grant.mode() != AccessMode.WRITE)) throw failure("Invalid storage grant");
        scope(context.txId);
    }
    private StoredRecord wrap(TableId table, sqlike.shared.Record record) {
        return new StoredRecord(table, new Rid(record.rid()), new Row(record.values()));
    }
    @Override public Schema getSchema(TableId table) { return new Schema(storage.getSchema(table.value())); }
    @Override public RecordCursor scan(TableId table, ExecutionContext context, AccessGrant grant) {
        access(table, context, grant, AccessMode.READ);
        var cursor = storage.scan(table.value());
        return new RecordCursor() {
            @Override public Optional<StoredRecord> next() {
                context.check(); return cursor.hasNext() ? Optional.of(wrap(table, cursor.next())) : Optional.empty();
            }
            @Override public void close() { cursor.close(); }
        };
    }
    @Override public Optional<StoredRecord> fetch(TableId table, Rid rid, ExecutionContext context, AccessGrant grant) {
        access(table, context, grant, AccessMode.READ);
        return storage.fetch(table.value(), rid.value()).map(record -> wrap(table, record));
    }
    @Override public Rid reserveInsert(TableId table, Row row, ExecutionContext context, AccessGrant grant) {
        access(table, context, grant, AccessMode.WRITE);
        InsertReservation reservation = storage.reserveInsert(table.value(), row.values());
        Rid rid = new Rid(reservation.rid()); reservations.put(rid, new Reservation(context.txId, reservation)); return rid;
    }
    @Override public void releaseReservation(TableId table, Rid rid, TxId tx) {
        Reservation reservation = reservations.get(rid);
        if (reservation != null && reservation.tx().equals(tx)) {
            scope(tx); storage.releaseReservation(reservation.value()); reservations.remove(rid, reservation);
        }
    }
    @Override public void releaseReservations(TxId tx) {
        reservations.forEach((rid, reservation) -> { if (reservation.tx().equals(tx)) releaseReservation(null, rid, tx); });
    }
    @Override public void applyChange(ChangeRecord change, WritePermit permit) {
        if (!change.id().equals(permit.changeId()) || permit.lsn().value() <= 0) throw failure("Invalid write permit");
        scope(change.txId()); var data = change.value(); long lsn = permit.lsn().value();
        switch (data.operation()) {
            case INSERT -> {
                Reservation reservation = Optional.ofNullable(reservations.get(new Rid(data.rid())))
                        .orElseThrow(() -> failure("Missing insert reservation"));
                if (!reservation.tx().equals(change.txId())) throw failure("Reservation belongs to another transaction");
                if (!data.rid().equals(storage.insert(reservation.value(), lsn))) throw failure("SM changed the reserved RID");
            }
            case UPDATE -> storage.update(data.tableName(), data.rid(), data.after().values(), lsn);
            case DELETE -> storage.delete(data.tableName(), data.rid(), lsn);
        }
    }
    @Override public RidCursor findIndex(TableId table, String indexId, IndexSearch search, ExecutionContext context, AccessGrant grant) {
        access(table, context, grant, AccessMode.READ); var matches = storage.lookupIndex(indexId, search.value()).iterator();
        return new RidCursor() {
            private boolean closed;
            @Override public Optional<Rid> next() { context.check(); return !closed && matches.hasNext() ? Optional.of(new Rid(matches.next())) : Optional.empty(); }
            @Override public void close() { closed = true; }
        };
    }
    @Override public void createIndex(CreateIndexSpec spec, ExecutionContext context, AccessGrant grant) {
        access(spec.tableId(), context, grant, AccessMode.WRITE); storage.createIndex(spec.value());
    }
    @Override public void applyRecovery(LogRecord record, RecoveryDirection direction, RecoveryContext context) {
        var change = record.change().value();
        storage.restoreRecord(change.tableName(), change.rid(), direction == RecoveryDirection.UNDO ? change.before() : change.after(), record.lsn().value());
    }
}
