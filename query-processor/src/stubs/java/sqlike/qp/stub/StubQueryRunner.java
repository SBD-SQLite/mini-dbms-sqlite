package sqlike.qp.stub;

import java.util.ArrayList;
import java.util.List;
import sqlike.contract.IQueryOptimizer;
import sqlike.qo.PlanNodes;
import sqlike.qo.QueryOptimizer;
import sqlike.qp.AccessCoordinator;
import sqlike.qp.CommandExecutor;
import sqlike.qp.ExecutionContext;
import sqlike.qp.ExecutionEngine;
import sqlike.qp.ExecutionSummary;
import sqlike.qp.ExpressionEvaluator;
import sqlike.qp.IExpressionEvaluator;
import sqlike.qp.IValueComparator;
import sqlike.qp.MutationCoordinator;
import sqlike.qp.MutationTargetCollector;
import sqlike.qp.Operator;
import sqlike.qp.PlanTranslator;
import sqlike.qp.PlanValidator;
import sqlike.qp.PreparedStatement;
import sqlike.qp.QpConfiguration;
import sqlike.qp.ResultSink;
import sqlike.qp.Row;
import sqlike.qp.RowSizeEstimator;
import sqlike.qp.Schema;
import sqlike.qp.SessionState;
import sqlike.qp.TransactionCoordinator;
import sqlike.qp.ValueComparator;
import sqlike.qp.port.QpContracts.DbmsError;
import sqlike.qp.port.QpContracts.IConcurrencyControl;
import sqlike.qp.port.QpContracts.TransactionState;
import sqlike.qp.port.StorageAdapter;
import sqlike.shared.ParsedQuery;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.QueryPlan;
import static sqlike.qp.port.QpContracts.failure;

/**
 * Integrates all real QP internal components (QP1 ExecutionEngine, QP2 PlanTranslator/PlanValidator/
 * ExpressionEvaluator/ValueComparator, QP3 Scan/IndexScan/Filter/Project/Delete, QP4 BNLJ/Sort,
 * QP5 Insert/Update/CreateIndex/TransactionCoordinator/AccessCoordinator/MutationCoordinator)
 * with external component stubs (SM and CCM/FRM).
 */
public final class StubQueryRunner {
    public record Result(boolean success, long affected, List<Row> rows, String message) { }

    public final IQueryOptimizer optimizer;
    public final StorageAdapter storage;
    public final TransactionCoordinator transactions;
    public final AccessCoordinator access;
    public final MutationCoordinator mutations;
    public final PlanTranslator translator;
    public final ExecutionEngine engine;
    private final IValueComparator comparator = new ValueComparator();
    private final IExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);

    public StubQueryRunner(
            sqlike.contract.IStorageManager sm,
            IConcurrencyControl concurrency,
            QpConfiguration config) {
        this.optimizer = new QueryOptimizer(sm);
        this.storage = new StorageAdapter(sm);
        this.transactions = new TransactionCoordinator(concurrency, config);
        this.access = new AccessCoordinator(concurrency);
        this.mutations = new MutationCoordinator(storage, concurrency);
        this.translator = PlanTranslator.withQpStorage(
                storage,
                evaluator,
                comparator,
                mutations,
                new MutationTargetCollector(sm),
                new RowSizeEstimator(),
                new PlanValidator());
        this.engine = new ExecutionEngine(translator);
    }

    public Result execute(SessionState session, String sql) {
        synchronized (session) {
            try {
                ParsedQuery parsed = optimizer.parseQuery(sql);
                if (parsed.transactionCommand().isPresent()) {
                    return switch (parsed.transactionCommand().get().action()) {
                        case BEGIN -> new Result(
                                true,
                                0,
                                List.of(),
                                "BEGIN: ACTIVE txId=" + transactions.beginExplicit(session).value());
                        case COMMIT -> completion("COMMIT", transactions.commit(session), TransactionState.COMMITTED);
                        case ABORT -> completion("ABORT", transactions.abort(session, "User ABORT"), TransactionState.ABORTED);
                    };
                }
                return executePlan(session, optimizer.optimizeQuery(parsed).root());
            } catch (RuntimeException e) {
                return new Result(false, 0, List.of(), "ERROR: " + e.getMessage());
            }
        }
    }

    private Result completion(String operation, TransactionState state, TransactionState expected) {
        return new Result(
                state == expected,
                0,
                List.of(),
                operation + (state == expected ? ": " : " rejected: ") + state);
    }

    public Result executePlan(SessionState session, PlanNode plan) {
        synchronized (session) {
            ExecutionContext context = null;
            try {
                PlanNodes.validate(plan);
                PreparedStatement prepared = PreparedStatement.of(new QueryPlan(plan, 0.0));
                translator.validate(prepared);
                if (plan.kind() == PlanNodeKind.CREATE_INDEX && session.isExplicitTransaction()) {
                    throw failure("CREATE INDEX is not allowed within an explicit transaction");
                }
                context = transactions.ensureTransaction(session);
                access.acquireAll(prepared, context);

                boolean isCommand = plan.kind() == PlanNodeKind.INSERT
                        || plan.kind() == PlanNodeKind.UPDATE
                        || plan.kind() == PlanNodeKind.DELETE
                        || plan.kind() == PlanNodeKind.CREATE_INDEX;

                if (!isCommand) {
                    var rows = new ArrayList<Row>();
                    Operator source = translator.translateSelect(plan);
                    engine.executeSelect(source, context, new ResultSink() {
                        @Override
                        public void onSchema(Schema schema) {}

                        @Override
                        public void onRow(Row row) {
                            rows.add(row);
                        }

                        @Override
                        public void onFinish(ExecutionSummary summary) {}
                    });
                    transactions.finishImplicit(session, context);
                    return new Result(true, 0, List.copyOf(rows), rows.size() + " row(s) read");
                }

                CommandExecutor executor = translator.translateCommand(plan);
                long count = engine.executeCommand(executor, context);
                transactions.finishImplicit(session, context);
                return new Result(
                        true,
                        count,
                        List.of(),
                        plan.kind() == PlanNodeKind.CREATE_INDEX
                                ? "Index " + plan.createIndex().orElseThrow().indexName() + " created successfully"
                                : count + " row(s) affected");
            } catch (RuntimeException e) {
                boolean isExplicitCreateIndexRejection = plan != null
                        && plan.kind() == PlanNodeKind.CREATE_INDEX
                        && e.getMessage() != null
                        && e.getMessage().contains("CREATE INDEX is not allowed within an explicit transaction");
                if (context != null && !isExplicitCreateIndexRejection) {
                    try {
                        transactions.handleRuntimeFailure(session, context, new DbmsError(e.getMessage()));
                    } catch (RuntimeException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                }
                return new Result(
                        false,
                        0,
                        List.of(),
                        "ERROR: " + e.getMessage()
                                + (e.getSuppressed().length == 0
                                        ? ""
                                        : "; cleanup incomplete: " + e.getSuppressed()[0].getMessage()));
            }
        }
    }
}
