package sqlike.qp.port;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import sqlike.shared.*;

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
    public enum AccessMode { READ, WRITE }
    public enum AccessStatus { GRANTED, WAIT, ABORTED }
    public record Row(List<Value> values) { public Row { values = List.copyOf(values); } }
    public record Schema(TableSchema value) {
        public int offset(ColumnRef column) {
            if (column.tableName().isPresent() && !column.tableName().get().equals(value.tableName()))
                throw failure("Column belongs to another table: " + column);
            for (int i = 0; i < value.columns().size(); i++)
                if (value.columns().get(i).name().equals(column.columnName())) return i;
            throw failure("Unknown column: " + column.columnName());
        }
        public Row validate(Row row) {
            if (row.values().size() != value.columns().size()) throw failure("Wrong number of INSERT/UPDATE values");
            var copy = new ArrayList<Value>();
            for (int i = 0; i < row.values().size(); i++) copy.add(coerce(i, row.values().get(i)));
            return new Row(copy);
        }
        public Value coerce(int offset, Value input) {
            ColumnSchema column = value.columns().get(offset);
            Object payload = payload(input);
            if (column.dataType() == DataType.FLOAT && input.type() == DataType.INTEGER)
                input = number(((Integer) payload).doubleValue());
            else if ((column.dataType() == DataType.CHAR || column.dataType() == DataType.VARCHAR) && payload instanceof String)
                input = new Value(column.dataType(), Optional.empty(), Optional.empty(), Optional.of((String) payload));
            if (input.type() != column.dataType()) throw failure("Type mismatch: " + column.name());
            if (payload instanceof String text && column.maxLength().isPresent() && text.length() > column.maxLength().get())
                throw failure("Text too long: " + column.name());
            return input;
        }
    }
    public record StoredRecord(TableId tableId, Rid rid, Row row) { }
    public record RowEnvelope(TableId tableId, Rid rid, Row row) { }
    public record Expr(Expression value) { }
    public record ExpressionResult(Value value, Boolean condition) { }
    public record InsertSpec(TableId tableId, Row row) { }
    public record UpdateSpec(TableId tableId, ColumnRef column, Expr value) { }
    public record CreateIndexSpec(IndexMetadata value) { public TableId tableId() { return new TableId(value.tableName()); } }
    public record IndexScanSpec(ScanSpec value) { public TableId tableId() { return new TableId(value.tableName()); } }
    public record IndexSearch(IndexCondition value) { }
    public record ChangeRecord(ChangeId id, TxId txId, DataChange value) { }
    public record WritePermit(ChangeId changeId, Lsn lsn) { }
    public record RecoveryReport(boolean success, long lastTxId) { }
    public record LogRecord(ChangeRecord change, Lsn lsn) { }
    public enum RecoveryDirection { UNDO, REDO }
    public record RecoveryContext(String description) { }
    public record QpConfiguration(int port, int maxClients, long joinBufferBytes,
                                  IndexType defaultIndexKind, Path temporaryDirectory) { }
    public record DbmsError(String message) { }
    public record ResourceKey(TableId tableId, Optional<Rid> rid) {
        public static ResourceKey table(TableId table) { return new ResourceKey(table, Optional.empty()); }
    }
    public record AccessGrant(TxId txId, ResourceKey resource, AccessMode mode) { }
    public record AccessDecision(AccessStatus status, Optional<AccessGrant> grant, UUID requestId) { }
    public record AccessRequest(ResourceKey resource, AccessMode mode) { }
    public record PreparedStatement(List<AccessRequest> accesses) { }
    public static final class CancellationToken {
        private volatile boolean cancelled;
        public void cancel() { cancelled = true; }
        public void check() { if (cancelled) throw failure("Transaction cancelled"); }
    }
    public static final class ExecutionContext {
        public final SessionId sessionId;
        public final TxId txId;
        public final boolean implicit;
        private final AtomicLong sequence;
        private final CancellationToken cancellation;
        private final Map<ResourceKey, AccessGrant> grants = new HashMap<>();
        public ExecutionContext(SessionId session, TxId tx, boolean implicit, AtomicLong sequence, CancellationToken token) {
            sessionId = session; txId = tx; this.implicit = implicit; this.sequence = sequence; cancellation = token;
        }
        public void check() { cancellation.check(); }
        public ChangeId nextChangeId() { return new ChangeId(txId, sequence.incrementAndGet()); }
        public void addGrant(AccessGrant grant) {
            if (!txId.equals(grant.txId())) throw failure("Grant belongs to another transaction");
            grants.compute(grant.resource(), (key, old) -> old != null && old.mode() == AccessMode.WRITE ? old : grant);
        }
        public AccessGrant requireGrant(TableId table, AccessMode mode) {
            check(); AccessGrant grant = grants.get(ResourceKey.table(table));
            if (grant == null || (mode == AccessMode.WRITE && grant.mode() != AccessMode.WRITE))
                throw failure("Missing " + mode + " grant for " + table.value());
            return grant;
        }
    }
    public interface WriteLease extends AutoCloseable { @Override void close(); }
    public interface RecordCursor extends AutoCloseable {
        Optional<StoredRecord> next();
        @Override void close();
    }
    public interface RidCursor extends AutoCloseable { Optional<Rid> next(); @Override void close(); }
    public interface Operator extends AutoCloseable {
        Schema schema(); void open(ExecutionContext context); Optional<RowEnvelope> next(); @Override void close();
    }
    public interface IExpressionEvaluator {
        ExpressionResult evaluate(Expr expr, Row row, Schema schema);
        boolean evaluateCondition(Expr expr, Row row, Schema schema);
    }
    public interface IConcurrencyControl {
        void initialize(RecoveryReport report); TxId begin(SessionId sessionId);
        AccessDecision acquireAccess(TxId txId, ResourceKey resource, AccessMode mode);
        AccessDecision waitAccess(UUID requestId); WritePermit authorizeChange(ChangeRecord change);
        WriteLease acquireApplyLease(WritePermit permit); void confirmApplied(ChangeId changeId);
        TransactionState getState(TxId txId); CancellationToken getCancellationToken(TxId txId);
        TransactionState commit(TxId txId); TransactionState abort(TxId txId, String reason);
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
