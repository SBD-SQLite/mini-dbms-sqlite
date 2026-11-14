package sqlike.qp.port;

import java.util.*;
import sqlike.shared.*;
import sqlike.qp.*;

/** Local QP contracts using the existing shared value, expression, RID, schema, and plan types. */
public final class QpContracts {
    private QpContracts() { }
    public record SessionId(UUID value) { }
    public record TxId(long value) { }
    public record TableId(String value) { }
    public record Rid(RID value) { }
    public record Lsn(long value) { }
    public record ChangeId(TxId txId, long sequence) { }
    public enum TransactionState { ACTIVE, PARTIALLY_COMMITTED, FAILED, ABORTED, COMMITTED }
    public enum TransactionEvent { BEGIN, COMMIT, ABORT }
    public enum AccessMode { READ, WRITE }
    public record Expr(Expression value) { }
    public record IndexSearch(IndexCondition value) { }
    public record ChangeRecord(ChangeId id, TxId txId, DataChange value) { }
    public record SchemaChangeRecord(ChangeId id, TxId txId, SchemaChange value) { }
    public record WritePermit(ChangeId changeId, Lsn lsn) { }
    public record RecoveryReport(boolean success, long lastTxId) { }
    public record LogRecord(ChangeRecord change, Lsn lsn) { }
    public enum RecoveryDirection { UNDO, REDO }
    public record RecoveryContext(String description) { }
    public record DbmsError(String message) { }
    public record ResourceKey(TableId tableId, Optional<Rid> rid) {
        public static ResourceKey table(TableId table) { return new ResourceKey(table, Optional.empty()); }
    }
    public record AccessGrant(TxId txId, ResourceKey resource, AccessMode mode) { }
    public record AccessDecision(AccessStatus status, Optional<AccessGrant> grant, UUID requestId) { }
    public record AccessRequest(ResourceKey resource, AccessMode mode) { }
    public static final class CancellationToken {
        private volatile boolean cancelled;
        public void cancel() { cancelled = true; }
        public void check() { if (cancelled) throw failure("Transaction cancelled"); }
    }
    public interface WriteLease extends AutoCloseable { @Override void close(); }
    public interface RecordCursor extends AutoCloseable {
        Optional<StoredRecord> next();
        @Override void close();
    }
    public interface RidCursor extends AutoCloseable { Optional<Rid> next(); @Override void close(); }
    public interface IConcurrencyControl {
        void initialize(RecoveryReport report); TxId begin(SessionId sessionId);
        AccessDecision acquireAccess(TxId txId, ResourceKey resource, AccessMode mode);
        AccessDecision waitAccess(UUID requestId); WritePermit authorizeChange(ChangeRecord change);
        default WritePermit authorizeSchemaChange(SchemaChangeRecord change) {
            return new WritePermit(change.id(), new Lsn(1L));
        }
        WriteLease acquireApplyLease(WritePermit permit); void confirmApplied(ChangeId changeId);
        TransactionState getState(TxId txId); CancellationToken getCancellationToken(TxId txId);
        TransactionState commit(TxId txId); TransactionState abort(TxId txId, String reason);
    }
    public interface IFailureRecovery {
        Lsn appendEvent(TxId txId, TransactionEvent event);
        Lsn appendChange(ChangeRecord change);
        void forceLog(Lsn lsn);
        void rollback(TxId txId);
        RecoveryReport recover();
    }
    public interface IStorageManager {
        Schema getSchema(TableId tableId);
        RecordCursor scan(TableId tableId, ExecutionContext context, AccessGrant grant);
        Optional<StoredRecord> fetch(TableId tableId, Rid rid, ExecutionContext context, AccessGrant grant);
        Rid reserveInsert(TableId tableId, Row row, ExecutionContext context, AccessGrant grant);
        void releaseReservation(TableId tableId, Rid rid, TxId txId); void releaseReservations(TxId txId);
        void applyChange(ChangeRecord change, WritePermit permit);
        RidCursor findIndex(TableId tableId, String indexId, IndexSearch search, ExecutionContext context, AccessGrant grant);
        void createIndex(CreateIndexSpec spec, ExecutionContext context, AccessGrant grant);
        void applyRecovery(LogRecord record, RecoveryDirection direction, RecoveryContext context);
        default List<String> listTables() { return List.of(); }
        default TableSchema getTableSchema(TableId tableId) {
            Schema s = getSchema(tableId);
            return new TableSchema(tableId.value(), tableId.value(), s != null ? s.columns() : List.of());
        }
        default List<IndexMetadata> getIndexes(TableId tableId) { return List.of(); }
        default String createSnapshot() { return "snap-0"; }
        default void createTable(TableSchema schema, ExecutionContext context, AccessGrant grant, long lsn) {
            throw failure("createTable is not implemented");
        }
        default void dropTable(TableId tableId, DropBehavior behavior, ExecutionContext context, AccessGrant grant, long lsn) {
            throw failure("dropTable is not implemented");
        }
    }
    public static IllegalStateException failure(String message) { return new IllegalStateException(message); }
    public static Object payload(Value value) {
        return switch (value.type()) {
            case INTEGER -> value.integerValue().orElseThrow();
            case FLOAT -> { double n = value.floatValue().orElseThrow(); if (!Double.isFinite(n)) throw failure("Non-finite number"); yield n; }
            case CHAR, VARCHAR -> value.textValue().orElseThrow();
        };
    }
    public static Value number(double value) {
        if (!Double.isFinite(value)) throw failure("Non-finite number");
        return new Value(DataType.FLOAT, Optional.empty(), Optional.of(value), Optional.empty());
    }
}
