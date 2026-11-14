package sqlike.qp;

import java.util.ArrayList;
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
import sqlike.qo.QoException;
import sqlike.shared.QueryPlan;
import sqlike.shared.QueryResult;
import sqlike.shared.RecoveryResult;
import sqlike.shared.ResultColumn;
import sqlike.shared.ResultKind;
import sqlike.shared.Session;
import sqlike.shared.StatementType;
import sqlike.shared.TransactionAction;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;
import sqlike.shared.Value;

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

    private record Outcome(ExecutionSummary summary, Optional<TransactionResponse> transaction) {
    }

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
        this(optimizer, storage, concurrency, recovery, QpConfiguration.defaults());
    }

    public QueryProcessor(
            IQueryOptimizer optimizer,
            IStorageManager storage,
            IConcurrencyControlManager concurrency,
            IFailureRecoveryManager recovery,
            QpConfiguration configuration) {
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
        if (configuration == null) {
            throw new IllegalArgumentException("QpConfiguration cannot be null in QueryProcessor");
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
                storageAdapter, evaluator, comparator, mutations, collector, estimator, validator, configuration);
        this.engine = new ExecutionEngine(this.translator);
        this.sessions = new SessionManager();
        this.transactions = new TransactionCoordinator(this.concurrencyAdapter, configuration, storageAdapter);
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
        this.state = SystemState.STARTING;
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

    void markReady() {
        this.state = SystemState.READY;
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
                session.getCurrentTxId().ifPresent(this::abortQuietly);
                session.clearTransaction();
            }
            sessions.removeClosed(sessionId);
        } finally {
            session.unlockExecution();
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
        } catch (RuntimeException e) {
            return finish(sink, ExecutionSummary.failure(toErrorInfo(e)));
        }

        if (!isFullyWired()) {
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
            return finish(sink, ExecutionSummary.fromQueryResult(qr));
        }
        return finish(sink, run(session, sql, sink).summary());
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

        if (isFullyWired()) {
            return runForResult(sessionState, sql);
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
            if (concurrencyAdapter != null) {
                concurrencyAdapter.registerExternalTransaction(txId);
            }
            if (access != null) {
                if (!access.isTransactionActive(new TxId(txId))) {
                    throw new QpException(ErrorCode.INVALID_STATE, "Execution requires an active transaction");
                }
                context = transactions.externalContext(txId);
                translator.validate(PreparedStatement.of(plan));
                if (isSchemaStatement(plan.root().kind().name())) {
                    throw new QpException(ErrorCode.INVALID_STATE, "DDL requires auto-commit SQL execution");
                }
                access.acquireCatalog(false, context);
                access.acquireAll(PreparedStatement.of(plan), context);
            }
            return planExecutor.execute(plan, context);
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

    private boolean isFullyWired() {
        return transactions != null && translator != null && access != null;
    }

    private ExecutionSummary finish(ResultSink sink, ExecutionSummary summary) {
        if (sink != null) {
            sink.onFinish(summary);
        }
        return summary;
    }

    private Outcome run(SessionState session, String sql, ResultSink sink) {
        if (sql == null || sql.isBlank()) {
            return new Outcome(
                    ExecutionSummary.failure(toErrorInfo(
                            new QpException(ErrorCode.SYNTAX_ERROR, "SQL statement cannot be empty"))),
                    Optional.empty());
        }
        session.lockExecution();
        Optional<ExecutionContext> context = Optional.empty();
        try {
            ParsedQuery parsed = optimizer.parseQuery(sql);
            if (parsed.transactionCommand().isPresent()) {
                return runTransactionCommand(session, parsed.transactionCommand().get().action());
            }
            boolean schemaStatement = isSchemaStatement(parsed.statementType().name());
            if (schemaStatement && session.isExplicitTransaction()) {
                throw new QpException(ErrorCode.INVALID_STATE, "DDL is not allowed within an explicit transaction");
            }
            ExecutionContext ctx = transactions.ensureTransaction(session);
            context = Optional.of(ctx);
            access.acquireCatalog(schemaStatement, ctx);
            // Binding errors leave explicit transactions usable; implicit transactions still need cleanup.
            if (!ctx.implicit) context = Optional.empty();
            QueryPlan plan = optimizer.optimizeQuery(parsed);
            PreparedStatement prepared = PreparedStatement.of(sql, plan);
            validateStatementPolicy(prepared, session);
            context = Optional.of(ctx);
            ExecutionSummary summary = executePrepared(prepared, ctx, sink);
            transactions.finishImplicit(session, ctx);
            return new Outcome(summary, Optional.empty());
        } catch (RuntimeException e) {
            return new Outcome(handleFailure(session, context, toErrorInfo(e)), Optional.empty());
        } finally {
            session.unlockExecution();
        }
    }

    private Outcome runTransactionCommand(SessionState session, TransactionAction action) {
        switch (action) {
            case BEGIN: {
                TxId tx = transactions.beginExplicit(session);
                return transactionOutcome(tx.value(), TransactionStatus.ACTIVE, "BEGIN: ACTIVE txId=" + tx.value());
            }
            case COMMIT: {
                long txId = requireExplicitTransaction(session, "COMMIT");
                TransactionState st = transactions.commit(session);
                if (st == TransactionState.PARTIALLY_COMMITTED) {
                    throw new QpException(ErrorCode.INVALID_STATE, "Commit outcome unknown. Recovery required.");
                }
                if (st != TransactionState.COMMITTED) {
                    return new Outcome(ExecutionSummary.failure(new ErrorInfo(
                            ErrorCode.TRANSACTION_ABORTED, ComponentType.CCM,
                            "COMMIT rejected: " + st, Optional.empty())), Optional.empty());
                }
                return transactionOutcome(txId, TransactionStatus.COMMITTED, "COMMIT: COMMITTED");
            }
            case ABORT:
            default: {
                long txId = requireExplicitTransaction(session, "ABORT");
                TransactionState st = transactions.abort(session, "User ABORT");
                if (st == TransactionState.PARTIALLY_COMMITTED) {
                    throw new QpException(ErrorCode.INVALID_STATE, "Commit outcome unknown. Recovery required.");
                }
                if (st != TransactionState.ABORTED) {
                    return new Outcome(ExecutionSummary.failure(new ErrorInfo(
                            ErrorCode.INVALID_STATE, ComponentType.CCM,
                            "ABORT rejected: " + st, Optional.empty())), Optional.empty());
                }
                return transactionOutcome(txId, TransactionStatus.ABORTED, "ABORT: ABORTED");
            }
        }
    }

    private static boolean isSchemaStatement(String kind) {
        return kind.equals("CREATE_INDEX") || kind.equals("CREATE_TABLE") || kind.equals("DROP_TABLE");
    }

    private Outcome transactionOutcome(long txId, TransactionStatus status, String message) {
        TransactionResponse response = new TransactionResponse(txId, txId, status, Optional.empty());
        return new Outcome(ExecutionSummary.ok(0L, message), Optional.of(response));
    }

    private QueryResult runForResult(SessionState session, String sql) {
        CollectingSink collector = new CollectingSink();
        Outcome outcome = run(session, sql, collector);
        ExecutionSummary summary = outcome.summary();
        if (!summary.isSuccess()) {
            return summary.toQueryResult();
        }
        if (outcome.transaction().isPresent()) {
            return transactionResult(outcome.transaction().get(), summary.getMessage());
        }
        if (collector.schema != null) {
            List<ResultColumn> columns = collector.schema.getColumns().stream()
                    .map(c -> new ResultColumn(c.name(), Optional.empty(), c.dataType()))
                    .toList();
            List<List<Value>> rows = collector.rows.stream().map(Row::values).toList();
            return new QueryResult(ResultKind.ROWS, columns, rows, 0, summary.getMessage(), Optional.empty());
        }
        return summary.toQueryResult();
    }

    private static final class CollectingSink implements ResultSink {
        private Schema schema;
        private final List<Row> rows = new ArrayList<>();

        @Override
        public void onSchema(Schema schema) {
            this.schema = schema;
        }

        @Override
        public void onRow(Row row) {
            rows.add(row);
        }

        @Override
        public void onFinish(ExecutionSummary summary) {
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
        ErrorInfo info = new ErrorInfo(
                ErrorCode.INVALID_STATE,
                ComponentType.QP,
                error != null ? error.message() : "Execution failed",
                Optional.empty());
        return handleFailure(session, started ? context : Optional.empty(), info);
    }

    private ExecutionSummary handleFailure(
            SessionState session,
            Optional<ExecutionContext> context,
            ErrorInfo error) {
        if (context.isPresent() && transactions != null) {
            try {
                transactions.handleRuntimeFailure(session, context.get(), new DbmsError(error.message()));
            } catch (RuntimeException cleanupError) {
                return ExecutionSummary.failure(new ErrorInfo(
                        error.code(),
                        error.component(),
                        error.message() + "; cleanup incomplete: " + cleanupError.getMessage(),
                        error.position()));
            }
        }
        return ExecutionSummary.failure(error);
    }

    // M1 driver path (PlanExecutor constructor)

    private QueryResult executeStatement(SessionState session, ParsedQuery parsed) {
        QueryPlan plan = optimizer.optimizeQuery(parsed);
        Optional<Long> activeTx = session.getCurrentTxId();
        if (activeTx.isPresent()) {
            return executeInExplicitTransaction(session, plan, activeTx.get());
        }
        return executeInImplicitTransaction(session, plan);
    }

    private QueryResult executeInExplicitTransaction(SessionState session, QueryPlan plan, long txId) {
        try {
            return planExecutor.execute(plan, ExecutionContext.of(txId, session.getId(), false));
        } catch (RuntimeException e) {
            ErrorInfo error = toErrorInfo(e);
            if (error.code() != ErrorCode.TRANSACTION_ABORTED) {
                abortQuietly(txId);
            }
            session.clearTransaction();
            return errorResult(error);
        }
    }

    private QueryResult executeInImplicitTransaction(SessionState session, QueryPlan plan) {
        TransactionResponse begin = requireStatus(concurrencyControl.beginTransaction(), TransactionStatus.ACTIVE);
        long txId = begin.txId();
        session.attachTransaction(txId, false);
        try {
            QueryResult result = planExecutor.execute(plan, ExecutionContext.of(txId, session.getId(), true));
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
        Optional<Long> activeTx = session.getCurrentTxId();
        if (activeTx.isPresent()) {
            throw new QpException(ErrorCode.INVALID_STATE, "Transaction " + activeTx.get() + " is already active");
        }
        TransactionResponse response = requireStatus(concurrencyControl.beginTransaction(), TransactionStatus.ACTIVE);
        session.attachTransaction(response.txId(), true);
        return transactionResult(response, "BEGIN TRANSACTION " + response.txId());
    }

    private QueryResult commit(SessionState session) {
        long txId = requireExplicitTransaction(session, "COMMIT");
        try {
            TransactionResponse response = requireStatus(concurrencyControl.commit(txId), TransactionStatus.COMMITTED);
            return transactionResult(response, "COMMIT " + txId);
        } finally {
            session.clearTransaction();
        }
    }

    private QueryResult abort(SessionState session) {
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
        if (e instanceof QoException qo && qo.getInfo() != null) {
            return qo.getInfo();
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
