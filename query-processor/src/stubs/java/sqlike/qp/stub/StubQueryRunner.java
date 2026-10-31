package sqlike.qp.stub;

import java.util.*;
import sqlike.contract.IQueryOptimizer;
import sqlike.qo.PlanNodes;
import sqlike.qo.QueryOptimizer;
import sqlike.shared.*;
import sqlike.qp.*;
import sqlike.qp.port.StorageAdapter;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.StubRuntime.*;
import static sqlike.qp.port.QpContracts.failure;

/** STUB ONLY: limited QP1/QP2/QP3 wiring; SQL parsing/optimization uses the existing real QO. */
public final class StubQueryRunner {
    public record Result(boolean success, long affected, List<Row> rows, String message) { }
    public final IQueryOptimizer optimizer;
    public final StorageAdapter storage;
    public final TransactionCoordinator transactions;
    public final AccessCoordinator access;
    public final MutationCoordinator mutations;
    private final StubExpressionEvaluator evaluator = new StubExpressionEvaluator();
    public StubQueryRunner(sqlike.contract.IStorageManager sm, IConcurrencyControl concurrency, QpConfiguration config) {
        optimizer = new QueryOptimizer(sm); storage = new StorageAdapter(sm);
        transactions = new TransactionCoordinator(concurrency, config); access = new AccessCoordinator(concurrency);
        mutations = new MutationCoordinator(storage, concurrency);
    }
    public Result execute(SessionState session, String sql) {
        synchronized (session) {
            try {
                ParsedQuery parsed = optimizer.parseQuery(sql);
                if (parsed.transactionCommand().isPresent()) {
                    return switch (parsed.transactionCommand().get().action()) {
                        case BEGIN -> new Result(true, 0, List.of(), "BEGIN: ACTIVE txId=" + transactions.beginExplicit(session).value());
                        case COMMIT -> completion("COMMIT", transactions.commit(session), TransactionState.COMMITTED);
                        case ABORT -> completion("ABORT", transactions.abort(session, "User ABORT"), TransactionState.ABORTED);
                    };
                }
                return executePlan(session, optimizer.optimizeQuery(parsed).root());
            } catch (RuntimeException e) { return new Result(false, 0, List.of(), "ERROR: " + e.getMessage()); }
        }
    }
    private Result completion(String operation, TransactionState state, TransactionState expected) {
        return new Result(state == expected, 0, List.of(), operation + (state == expected ? ": " : " rejected: ") + state);
    }
    public Result executePlan(SessionState session, PlanNode plan) {
        synchronized (session) {
            ExecutionContext context = null;
            try {
                PlanNodes.validate(plan); TableId table = table(plan);
                boolean command = Set.of(PlanNodeKind.INSERT, PlanNodeKind.UPDATE, PlanNodeKind.CREATE_INDEX).contains(plan.kind());
                if (!command && !Set.of(PlanNodeKind.SCAN, PlanNodeKind.INDEX_SCAN, PlanNodeKind.FILTER, PlanNodeKind.PROJECT).contains(plan.kind()))
                    throw failure("STUB QP2: unsupported plan " + plan.kind());
                context = transactions.ensureTransaction(session);
                access.acquireAll(new PreparedStatement(List.of(new AccessRequest(ResourceKey.table(table), command ? AccessMode.WRITE : AccessMode.READ))), context);
                if (!command) {
                    var rows = new ArrayList<Row>();
                    try (Operator source = source(plan)) {
                        source.open(context); Optional<RowEnvelope> next;
                        while ((next = source.next()).isPresent()) rows.add(next.get().row());
                    }
                    transactions.finishImplicit(session, context); return new Result(true, 0, List.copyOf(rows), rows.size() + " row(s) read");
                }
                long count;
                try (AbstractCommandExecutor executor = switch (plan.kind()) {
                    case INSERT -> new InsertExecutor(new InsertSpec(table, new Row(plan.mutation().orElseThrow().insertValues())), mutations);
                    case UPDATE -> new UpdateExecutor(source(plan.children().get(0)), new UpdateSpec(table,
                            plan.mutation().orElseThrow().updateColumn().orElseThrow(), new Expr(plan.mutation().orElseThrow().updateExpression().orElseThrow())),
                            storage.getSchema(table), new MutationTargetCollector(storage.getUnderlyingStorage()), evaluator, mutations);
                    case CREATE_INDEX -> new CreateIndexExecutor(new CreateIndexSpec(plan.createIndex().orElseThrow()), storage);
                    default -> throw failure("Unsupported command");
                }) { count = executor.execute(context); }
                transactions.finishImplicit(session, context);
                return new Result(true, count, List.of(), plan.kind() == PlanNodeKind.CREATE_INDEX
                        ? "Index " + plan.createIndex().orElseThrow().indexName() + " created successfully" : count + " row(s) affected");
            } catch (RuntimeException e) {
                boolean isExplicitCreateIndexRejection = plan != null && plan.kind() == PlanNodeKind.CREATE_INDEX
                        && e.getMessage() != null && e.getMessage().contains("CREATE INDEX is not allowed within an explicit transaction");
                if (context != null && !isExplicitCreateIndexRejection) {
                    try { transactions.handleRuntimeFailure(session, context, new DbmsError(e.getMessage())); }
                    catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
                }
                return new Result(false, 0, List.of(), "ERROR: " + e.getMessage()
                        + (e.getSuppressed().length == 0 ? "" : "; cleanup incomplete: " + e.getSuppressed()[0].getMessage()));
            }
        }
    }
    private TableId table(PlanNode plan) {
        if (plan.mutation().isPresent()) return new TableId(plan.mutation().get().tableName());
        if (plan.createIndex().isPresent()) return new TableId(plan.createIndex().get().tableName());
        if (plan.scan().isPresent()) return new TableId(plan.scan().get().tableName());
        if (plan.children().size() != 1) throw failure("STUB QP2 supports single-table QP5 inputs only");
        return table(plan.children().get(0));
    }
    private Operator source(PlanNode plan) {
        TableId table = table(plan); Schema schema = storage.getSchema(table);
        if (plan.kind() == PlanNodeKind.INDEX_SCAN) return new IndexScanOperator(storage.getUnderlyingStorage(), new IndexScanSpec(plan.scan().orElseThrow()), schema);
        if (plan.kind() == PlanNodeKind.SCAN) return new AbstractOperator(schema) {
            private sqlike.qp.port.QpContracts.RecordCursor cursor;
            @Override protected void doOpen() { cursor = storage.scan(table, context, context.requireGrant(table, AccessMode.READ)); }
            @Override protected Optional<RowEnvelope> doNext() { return cursor.next().map(r -> RowEnvelope.of(r.row(), r.rid())); }
            @Override protected void doClose() { if (cursor != null) { cursor.close(); cursor = null; } }
        };
        Operator child = source(plan.children().get(0));
        if (plan.kind() == PlanNodeKind.FILTER) return new AbstractOperator(child.schema()) {
            @Override protected void doOpen() { child.open(context); }
            @Override protected Optional<RowEnvelope> doNext() {
                Optional<RowEnvelope> next;
                while ((next = child.next()).isPresent())
                    if (evaluator.evaluateCondition(new Expr(plan.predicate().orElseThrow()), next.get().row(), child.schema())) return next;
                return Optional.empty();
            }
            @Override protected void doClose() { child.close(); }
        };
        if (plan.kind() != PlanNodeKind.PROJECT) throw failure("STUB QP3: unsupported source " + plan.kind());
        if (plan.projection().size() == 1 && plan.projection().get(0).columnName().equals("*")) return child;
        var offsets = plan.projection().stream().map(schema::offset).toList();
        var columns = offsets.stream().map(i -> schema.getColumn(i)).toList();
        return new AbstractOperator(Schema.of(table.value(), columns)) {
            @Override protected void doOpen() { child.open(context); }
            @Override protected Optional<RowEnvelope> doNext() {
                return child.next().map(row -> RowEnvelope.of(new Row(offsets.stream().map(i -> row.row().values().get(i)).toList()), row.rid().orElse(null)));
            }
            @Override protected void doClose() { child.close(); }
        };
    }
}
