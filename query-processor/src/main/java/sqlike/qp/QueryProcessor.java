package sqlike.qp;

import java.util.List;
import java.util.Optional;
import sqlike.contract.IConcurrencyControlManager;
import sqlike.contract.IFailureRecoveryManager;
import sqlike.contract.IQueryOptimizer;
import sqlike.contract.IQueryProcessor;
import sqlike.contract.IStorageManager;
import sqlike.qp.port.ConcurrencyAdapter;
import sqlike.qp.port.QpContracts.DbmsError;
import sqlike.qp.port.QpContracts.SessionId;
import sqlike.qp.port.QpContracts.TransactionState;
import sqlike.qp.port.QpContracts.TxId;
import sqlike.qp.port.StorageAdapter;
import sqlike.shared.ColumnSchema;
import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.ParsedQuery;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.QueryPlan;
import sqlike.shared.QueryResult;
import sqlike.shared.RecoveryResult;
import sqlike.shared.ResultKind;
import sqlike.shared.Session;
import sqlike.shared.StatementType;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;

/**
 * Query Processor facade implementing both the Supergroup Contract (Tab 6: IQueryProcessor)
 * and the detailed QP internal design (Tab 4: QueryProcessor).
 */
public final class QueryProcessor implements IQueryProcessor {
    private final IQueryOptimizer optimizer;
    private final IStorageManager storage;
    private final IConcurrencyControlManager concurrency;
    private final IConcurrencyControlManager concurrencyControl;
    private final IFailureRecoveryManager recovery;
    private final PlanTranslator translator;
    private final ExecutionEngine engine;
    private final SessionManager sessions;
    private final TransactionCoordinator transactions;
    private final AccessCoordinator access;
    private final PlanExecutor planExecutor;
    private final ConcurrencyAdapter concurrencyAdapter;
    private volatile SystemState state;

    /**
     * Constructor for QP1 M1 facade driver and PlanExecutor injection.
     */
    public QueryProcessor(
            IQueryOptimizer optimizer,
            IConcurrencyControlManager concurrencyControl,
            IFailureRecoveryManager recovery,
            PlanExecutor planExecutor,
            SessionManager sessions) {
        if (optimizer == null) {
            throw new IllegalArgumentException("IQueryOptimizer cannot be null in QueryProcessor");
        }
        if (concurrencyControl == null) {
            throw new IllegalArgumentException("IConcurrencyControlManager cannot be null in QueryProcessor");
        }
        if (recovery == null) {
            throw new IllegalArgumentException("IFailureRecoveryManager cannot be null in QueryProcessor");
        }
        if (planExecutor == null) {
            throw new IllegalArgumentException("PlanExecutor cannot be null in QueryProcessor");
        }
        if (sessions == null) {
            throw new IllegalArgumentException("SessionManager cannot be null in QueryProcessor");
        }
        this.optimizer = optimizer;
        this.storage = null;
        this.concurrency = concurrencyControl;
        this.concurrencyControl = concurrencyControl;
        this.recovery = recovery;
        this.translator = null;
        this.engine = planExecutor instanceof ExecutionEngine ee ? ee : new ExecutionEngine();
        this.sessions = sessions;
        this.transactions = null;
        this.access = null;
        this.planExecutor = planExecutor;
        this.concurrencyAdapter = null;
        this.state = SystemState.STARTING;
    }

    /**
     * Supergroup overview constructor (Tab 6) wiring all internal QP units (QP1-QP5).
     */
    public QueryProcessor(
            IQueryOptimizer optimizer,
            IStorageManager storage,
            IConcurrencyControlManager concurrency,
            IFailureRecoveryManager recovery) {
        if (optimizer == null) {
            throw new IllegalArgumentException("IQueryOptimizer cannot be null in QueryProcessor");
        }
        if (storage == null) {
            throw new IllegalArgumentException("IStorageManager cannot be null in QueryProcessor");
        }
        if (concurrency == null) {
            throw new IllegalArgumentException("IConcurrencyControlManager cannot be null in QueryProcessor");
        }
        if (recovery == null) {
            throw new IllegalArgumentException("IFailureRecoveryManager cannot be null in QueryProcessor");
        }
        this.optimizer = optimizer;
        this.storage = storage;
        this.concurrency = concurrency;
        this.concurrencyControl = concurrency;
        this.recovery = recovery;
        this.concurrencyAdapter = new ConcurrencyAdapter(concurrency, recovery);
        StorageAdapter storageAdapter = new StorageAdapter(storage);
        IValueComparator comparator = new ValueComparator();
        IExpressionEvaluator evaluator = new ExpressionEvaluator(comparator);
        MutationCoordinator mutations = new MutationCoordinator(storageAdapter, this.concurrencyAdapter);
        MutationTargetCollector collector = new MutationTargetCollector(storage);
        RowSizeEstimator estimator = new RowSizeEstimator();
        PlanValidator validator = new PlanValidator();
        this.translator = PlanTranslator.withQpStorage(
                storageAdapter, evaluator, comparator, mutations, collector, estimator, validator);
        this.engine = new ExecutionEngine(this.translator);
        this.sessions = new SessionManager();
        this.transactions = new TransactionCoordinator(this.concurrencyAdapter, QpConfiguration.defaults());
        this.access = new AccessCoordinator(this.concurrencyAdapter);
        this.planExecutor = this.engine;
        this.state = SystemState.STARTING;
    }

    /**
     * Detailed QP internal constructor matching CLASS DIAGRAM FIX (Tab 4).
     */
    public QueryProcessor(
            IQueryOptimizer optimizer,
            PlanTranslator translator,
            ExecutionEngine engine,
            SessionManager sessions,
            TransactionCoordinator transactions,
            AccessCoordinator access) {
        if (optimizer == null) {
            throw new IllegalArgumentException("IQueryOptimizer cannot be null in QueryProcessor");
        }
        if (translator == null) {
            throw new IllegalArgumentException("PlanTranslator cannot be null in QueryProcessor");
        }
        if (engine == null) {
            throw new IllegalArgumentException("ExecutionEngine cannot be null in QueryProcessor");
        }
        if (sessions == null) {
            throw new IllegalArgumentException("SessionManager cannot be null in QueryProcessor");
        }
        if (transactions == null) {
            throw new IllegalArgumentException("TransactionCoordinator cannot be null in QueryProcessor");
        }
        if (access == null) {
            throw new IllegalArgumentException("AccessCoordinator cannot be null in QueryProcessor");
        }
        this.optimizer = optimizer;
        this.storage = translator.getStorage();
        this.concurrency = null;
        this.concurrencyControl = null;
        this.recovery = null;
        this.translator = translator;
        this.engine = engine;
        this.sessions = sessions;
        this.transactions = transactions;
        this.access = access;
        this.planExecutor = engine;
        this.concurrencyAdapter = null;
        this.state = SystemState.READY;
    }

    @Override
    public synchronized void initialize() {
        if (state == SystemState.READY) {
            return;
        }
        state = SystemState.RECOVERING;
        try {
            if (recovery != null && concurrencyControl != null) {
                RecoveryResult result = recovery.recover();
                concurrencyControl.initAfterRecovery(result.lastTxId());
            }
            state = SystemState.READY;
        } catch (RuntimeException e) {
            state = SystemState.FAILED;
            throw e;
        }
    }

    public SystemState getState() {
        return state;
    }

    public synchronized void shutdown() {
        for (SessionState session : sessions.activeSessions()) {
            closeSession(session.getId());
        }
        state = SystemState.STOPPED;
    }

    public Session openSession() {
        requireReady();
        return sessions.openSession();
    }

    public SessionId openSessionId() {
        requireReady();
        return sessions.openSessionId();
    }

    public void closeSession(String sessionId) {
        SessionState session = sessions.markClosing(sessionId);
        session.lockExecution();
        try {
            if (transactions != null) {
                transactions.closeSession(session);
            } else if (concurrencyControl != null) {
                session.getCurrentTxId().ifPresent(txId -> {
                    try {
                        concurrencyControl.abort(txId);
                    } catch (RuntimeException ignored) {
                    }
                });
                session.clearTransaction();
            }
        } finally {
            session.unlockExecution();
            sessions.removeClosed(sessionId);
        }
    }

    public void closeSession(SessionId sessionId) {
        if (sessionId == null || sessionId.value() == null) {
            throw new QpException(ErrorCode.INVALID_STATE, "SessionId cannot be null in closeSession");
        }
        closeSession(sessionId.value().toString());
    }

    /**
     * Tab 4 execute signature streaming results into a ResultSink and returning an ExecutionSummary.
     */
    public ExecutionSummary execute(SessionId sessionId, String sql, ResultSink sink) {
        SessionState session;
        try {
            requireReady();
            session = sessions.requireSession(sessionId);
            if (sql == null || sql.isBlank()) {
                throw new QpException(ErrorCode.SYNTAX_ERROR, "SQL statement cannot be empty");
            }
        } catch (RuntimeException e) {
            ExecutionSummary fail = ExecutionSummary.failure(new DbmsError(e.getMessage()));
            if (sink != null) {
                sink.onFinish(fail);
            }
            return fail;
        }

        if (transactions == null || translator == null || access == null) {
            QueryResult qr = execute(sql, session.toSession());
            if (sink != null && qr.kind() == ResultKind.ROWS) {
                Schema schema = Schema.of(qr.columns().stream()
                        .map(rc -> new ColumnSchema(rc.name(), rc.dataType(), Optional.empty()))
                        .toList());
                sink.onSchema(schema);
                for (var rowVals : qr.rows()) {
                    sink.onRow(Row.of(rowVals));
                }
            }
            ExecutionSummary summary = ExecutionSummary.fromQueryResult(qr);
            if (sink != null) {
                sink.onFinish(summary);
            }
            return summary;
        }

        session.lockExecution();
        Optional<ExecutionContext> context = Optional.empty();
        boolean started = false;
        try {
            ParsedQuery parsed = optimizer.parseQuery(sql);
            if (parsed.transactionCommand().isPresent()) {
                ExecutionSummary txSummary = switch (parsed.transactionCommand().get().action()) {
                    case BEGIN -> {
                        TxId tx = transactions.beginExplicit(session);
                        yield ExecutionSummary.ok(0L, "BEGIN: ACTIVE txId=" + tx.value());
                    }
                    case COMMIT -> {
                        TransactionState st = transactions.commit(session);
                        yield st == TransactionState.COMMITTED
                                ? ExecutionSummary.ok(0L, "COMMIT: COMMITTED")
                                : ExecutionSummary.failure(new DbmsError("COMMIT rejected: " + st));
                    }
                    case ABORT -> {
                        TransactionState st = transactions.abort(session, "User ABORT");
                        yield st == TransactionState.ABORTED
                                ? ExecutionSummary.ok(0L, "ABORT: ABORTED")
                                : ExecutionSummary.failure(new DbmsError("ABORT rejected: " + st));
                    }
                };
                if (sink != null) {
                    sink.onFinish(txSummary);
                }
                return txSummary;
            }
            QueryPlan plan = optimizer.optimizeQuery(parsed);
            PreparedStatement prepared = PreparedStatement.of(sql, plan);
            validateStatementPolicy(prepared, session);
            ExecutionContext ctx = transactions.ensureTransaction(session);
            context = Optional.of(ctx);
            started = true;
            ExecutionSummary summary = executePrepared(prepared, ctx, sink);
            transactions.finishImplicit(session, ctx);
            if (sink != null) {
                sink.onFinish(summary);
            }
            return summary;
        } catch (RuntimeException e) {
            ExecutionSummary failure = handleFailure(session, context, new DbmsError(e.getMessage()), started);
            if (sink != null) {
                sink.onFinish(failure);
            }
            return failure;
        } finally {
            session.unlockExecution();
        }
    }

    private void validateStatementPolicy(PreparedStatement statement, SessionState session) {
        if (statement == null || statement.plan() == null || statement.root() == null) {
            throw new IllegalArgumentException("PreparedStatement and plan root cannot be null");
        }
        translator.validate(statement);
        PlanNodeKind kind = statement.root().kind();
        if ((kind == PlanNodeKind.CREATE_INDEX
                || kind == PlanNodeKind.CREATE_TABLE
                || kind == PlanNodeKind.DROP_TABLE)
                && session.isExplicitTransaction()) {
            throw new QpException(
                    ErrorCode.INVALID_STATE,
                    kind + " is not allowed within an explicit transaction");
        }
    }

    private ExecutionSummary executePrepared(
            PreparedStatement statement,
            ExecutionContext context,
            ResultSink sink) {
        access.acquireAll(statement, context);
        PlanNode root = statement.root();
        PlanNodeKind kind = root.kind();
        if (kind == PlanNodeKind.INSERT
                || kind == PlanNodeKind.UPDATE
                || kind == PlanNodeKind.DELETE
                || kind == PlanNodeKind.CREATE_INDEX
                || kind == PlanNodeKind.CREATE_TABLE
                || kind == PlanNodeKind.DROP_TABLE) {
            CommandExecutor cmd = translator.translateCommand(root);
            long count = engine.executeCommand(cmd, context);
            String msg = switch (kind) {
                case CREATE_INDEX -> "Index " + root.createIndex().map(m -> m.indexName()).orElse("") + " created successfully";
                case CREATE_TABLE -> "Table " + root.createTable().map(c -> c.tableName()).orElse("") + " created successfully";
                case DROP_TABLE -> "Table " + root.dropTable().map(d -> d.tableName()).orElse("") + " dropped successfully";
                default -> count + " row(s) affected";
            };
            return ExecutionSummary.ok(count, msg);
        }
        Operator op = translator.translateSelect(root);
        long rows = engine.executeSelect(op, context, sink);
        return ExecutionSummary.ok(rows, rows + " row(s) returned");
    }

    private ExecutionSummary handleFailure(
            SessionState session,
            Optional<ExecutionContext> context,
            DbmsError error,
            boolean started) {
        if (started && context.isPresent() && transactions != null) {
            try {
                transactions.handleRuntimeFailure(session, context.get(), error);
            } catch (RuntimeException cleanupError) {
                return ExecutionSummary.failure(
                        new DbmsError(error.message() + "; cleanup incomplete: " + cleanupError.getMessage()));
            }
        }
        return ExecutionSummary.failure(error);
    }

    @Override
    public QueryResult execute(String sql, Session session) {
        SessionState sessionState;
        try {
            requireReady();
            if (sql == null || sql.isBlank()) {
                throw new QpException(ErrorCode.SYNTAX_ERROR, "SQL statement cannot be empty");
            }
            if (session == null) {
                throw new QpException(ErrorCode.INVALID_STATE, "Session is required to execute SQL");
            }
            sessionState = sessions.requireSession(session.sessionId());
        } catch (RuntimeException e) {
            return errorResult(e);
        }

        sessionState.lockExecution();
        try {
            ParsedQuery parsed = optimizer.parseQuery(sql);
            StatementType type = parsed.statementType();
            if (type == StatementType.BEGIN) {
                return begin(sessionState);
            }
            if (type == StatementType.COMMIT) {
                return commit(sessionState);
            }
            if (type == StatementType.ABORT) {
                return abort(sessionState);
            }
            return executeStatement(sessionState, parsed);
        } catch (RuntimeException e) {
            return errorResult(e);
        } finally {
            sessionState.unlockExecution();
        }
    }

    @Override
    public QueryResult executePlan(QueryPlan plan, long txId) {
        try {
            requireReady();
            ExecutionContext context = ExecutionContext.of(txId);
            if (concurrencyAdapter != null && access != null && plan != null) {
                concurrencyAdapter.registerExternalTransaction(txId);
                access.acquireAll(PreparedStatement.of(plan), context);
            }
            return planExecutor.execute(plan, context);
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    private QueryResult executeStatement(SessionState session, ParsedQuery parsed) {
        QueryPlan plan = optimizer.optimizeQuery(parsed);
        if (translator != null) {
            validateStatementPolicy(PreparedStatement.of("", plan), session);
        }
        Optional<Long> activeTx = session.getCurrentTxId();
        if (activeTx.isPresent()) {
            return executeInExplicitTransaction(session, plan, activeTx.get());
        }
        return executeInImplicitTransaction(session, plan);
    }

    private QueryResult executeInExplicitTransaction(SessionState session, QueryPlan plan, long txId) {
        try {
            ExecutionContext context = ExecutionContext.of(txId, session.getId(), false);
            if (access != null) {
                if (concurrencyAdapter != null) {
                    concurrencyAdapter.registerExternalTransaction(txId);
                }
                access.acquireAll(PreparedStatement.of("", plan), context);
            }
            return planExecutor.execute(plan, context);
        } catch (RuntimeException e) {
            ErrorInfo error = toErrorInfo(e);
            if (error.code() == ErrorCode.TRANSACTION_ABORTED) {
                session.clearTransaction();
            }
            return errorResult(error);
        }
    }

    private QueryResult executeInImplicitTransaction(SessionState session, QueryPlan plan) {
        if (concurrencyControl == null && transactions != null) {
            ExecutionContext ctx = transactions.ensureTransaction(session);
            try {
                if (access != null) {
                    access.acquireAll(PreparedStatement.of("", plan), ctx);
                }
                QueryResult result = planExecutor.execute(plan, ctx);
                transactions.finishImplicit(session, ctx);
                return result;
            } catch (RuntimeException e) {
                transactions.handleRuntimeFailure(session, ctx, new DbmsError(e.getMessage()));
                return errorResult(e);
            }
        }
        TransactionResponse begin = requireStatus(concurrencyControl.beginTransaction(), TransactionStatus.ACTIVE);
        long txId = begin.txId();
        session.attachTransaction(txId, false);
        if (concurrencyAdapter != null) {
            concurrencyAdapter.registerExternalTransaction(txId);
        }
        try {
            ExecutionContext context = ExecutionContext.of(txId, session.getId(), true);
            if (access != null) {
                access.acquireAll(PreparedStatement.of("", plan), context);
            }
            QueryResult result = planExecutor.execute(plan, context);
            requireStatus(concurrencyControl.commit(txId), TransactionStatus.COMMITTED);
            return result;
        } catch (RuntimeException e) {
            abortQuietly(txId);
            return errorResult(e);
        } finally {
            session.clearTransaction();
        }
    }

    private QueryResult begin(SessionState session) {
        if (concurrencyControl == null && transactions != null) {
            TxId tx = transactions.beginExplicit(session);
            TransactionResponse resp = new TransactionResponse(tx.value(), System.currentTimeMillis(), TransactionStatus.ACTIVE, Optional.empty());
            return transactionResult(resp, "BEGIN TRANSACTION " + tx.value());
        }
        Optional<Long> activeTx = session.getCurrentTxId();
        if (activeTx.isPresent()) {
            throw new QpException(ErrorCode.INVALID_STATE, "Transaction " + activeTx.get() + " is already active");
        }
        TransactionResponse response = requireStatus(concurrencyControl.beginTransaction(), TransactionStatus.ACTIVE);
        session.attachTransaction(response.txId(), true);
        if (concurrencyAdapter != null) {
            concurrencyAdapter.registerExternalTransaction(response.txId());
        }
        return transactionResult(response, "BEGIN TRANSACTION " + response.txId());
    }

    private QueryResult commit(SessionState session) {
        if (concurrencyControl == null && transactions != null) {
            long txId = requireExplicitTransaction(session, "COMMIT");
            TransactionState st = transactions.commit(session);
            if (st != TransactionState.COMMITTED) {
                throw new QpException(new ErrorInfo(ErrorCode.TRANSACTION_ABORTED, ComponentType.CCM, "COMMIT rejected: " + st, Optional.empty()));
            }
            TransactionResponse resp = new TransactionResponse(txId, System.currentTimeMillis(), TransactionStatus.COMMITTED, Optional.empty());
            return transactionResult(resp, "COMMIT " + txId);
        }
        long txId = requireExplicitTransaction(session, "COMMIT");
        try {
            TransactionResponse response = requireStatus(concurrencyControl.commit(txId), TransactionStatus.COMMITTED);
            return transactionResult(response, "COMMIT " + txId);
        } finally {
            session.clearTransaction();
        }
    }

    private QueryResult abort(SessionState session) {
        if (concurrencyControl == null && transactions != null) {
            long txId = requireExplicitTransaction(session, "ABORT");
            TransactionState st = transactions.abort(session, "User ABORT");
            if (st != TransactionState.ABORTED) {
                throw new QpException(new ErrorInfo(ErrorCode.INVALID_STATE, ComponentType.CCM, "ABORT rejected: " + st, Optional.empty()));
            }
            TransactionResponse resp = new TransactionResponse(txId, System.currentTimeMillis(), TransactionStatus.ABORTED, Optional.empty());
            return transactionResult(resp, "ABORT " + txId);
        }
        long txId = requireExplicitTransaction(session, "ABORT");
        try {
            TransactionResponse response = requireStatus(concurrencyControl.abort(txId), TransactionStatus.ABORTED);
            return transactionResult(response, "ABORT " + txId);
        } finally {
            session.clearTransaction();
        }
    }

    private long requireExplicitTransaction(SessionState session, String command) {
        return session.getCurrentTxId().orElseThrow(
                () -> new QpException(ErrorCode.INVALID_STATE, command + " requires an active transaction"));
    }

    private TransactionResponse requireStatus(TransactionResponse response, TransactionStatus expected) {
        if (response == null) {
            throw new QpException(ErrorCode.INVALID_STATE, "CCM returned no transaction response");
        }
        if (response.error().isPresent()) {
            throw new QpException(response.error().get());
        }
        if (response.status() != expected) {
            throw new QpException(new ErrorInfo(
                    response.status() == TransactionStatus.ABORTED
                            ? ErrorCode.TRANSACTION_ABORTED
                            : ErrorCode.INVALID_STATE,
                    ComponentType.CCM,
                    "Transaction " + response.txId() + " is " + response.status() + ", expected " + expected,
                    Optional.empty()));
        }
        return response;
    }

    private void abortQuietly(long txId) {
        try {
            concurrencyControl.abort(txId);
        } catch (RuntimeException ignored) {
        }
    }

    private void requireReady() {
        if (state != SystemState.READY) {
            throw new QpException(ErrorCode.INVALID_STATE, "Query Processor is not ready (state: " + state + ")");
        }
    }

    private QueryResult transactionResult(TransactionResponse response, String message) {
        return new QueryResult(
                ResultKind.TRANSACTION, List.of(), List.of(), 0, Optional.of(response), message, Optional.empty());
    }

    private QueryResult errorResult(RuntimeException e) {
        return errorResult(toErrorInfo(e));
    }

    private QueryResult errorResult(ErrorInfo error) {
        return new QueryResult(ResultKind.ERROR, List.of(), List.of(), 0, error.message(), Optional.of(error));
    }

    private ErrorInfo toErrorInfo(RuntimeException e) {
        if (e instanceof QpException qp && qp.getErrorInfo() != null) {
            return qp.getErrorInfo();
        }
        ErrorCode code;
        if (e instanceof UnsupportedOperationException) {
            code = ErrorCode.UNSUPPORTED_OPERATION;
        } else if (e instanceof IllegalArgumentException) {
            code = ErrorCode.INVALID_VALUE;
        } else {
            code = ErrorCode.INVALID_STATE;
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ErrorInfo(code, ComponentType.QP, message, Optional.empty());
    }
}
