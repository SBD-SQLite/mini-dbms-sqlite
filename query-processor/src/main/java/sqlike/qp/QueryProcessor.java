package sqlike.qp;

import java.util.List;
import java.util.Optional;
import sqlike.contract.IConcurrencyControlManager;
import sqlike.contract.IFailureRecoveryManager;
import sqlike.contract.IQueryOptimizer;
import sqlike.contract.IQueryProcessor;
import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.ParsedQuery;
import sqlike.shared.QueryPlan;
import sqlike.shared.QueryResult;
import sqlike.shared.RecoveryResult;
import sqlike.shared.ResultKind;
import sqlike.shared.Session;
import sqlike.shared.StatementType;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;

/**
 * Query Processor facade: entry point for every SQL statement of a session.
 * Parses and optimizes through QO, controls transaction boundaries through CCM,
 * runs plans through PlanExecutor, and turns every failure into an ERROR QueryResult.
 */
public final class QueryProcessor implements IQueryProcessor {
    private final IQueryOptimizer optimizer;
    private final IConcurrencyControlManager concurrencyControl;
    private final IFailureRecoveryManager recovery;
    private final PlanExecutor planExecutor;
    private final SessionManager sessions;
    private volatile SystemState state;

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
        this.concurrencyControl = concurrencyControl;
        this.recovery = recovery;
        this.planExecutor = planExecutor;
        this.sessions = sessions;
        this.state = SystemState.STARTING;
    }

    @Override
    public synchronized void initialize() {
        if (state == SystemState.READY) {
            return;
        }
        state = SystemState.RECOVERING;
        try {
            RecoveryResult result = recovery.recover();
            concurrencyControl.initAfterRecovery(result.lastTxId());
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

    public void closeSession(String sessionId) {
        SessionState session = sessions.markClosing(sessionId);
        session.lockExecution();
        try {
            session.getCurrentTxId().ifPresent(txId -> {
                try {
                    concurrencyControl.abort(txId);
                } catch (RuntimeException ignored) {
                    // TODO(M2): log abort failure; the session is closed regardless.
                }
            });
            session.clearTransaction();
        } finally {
            session.unlockExecution();
            sessions.removeClosed(sessionId);
        }
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
            return planExecutor.execute(plan, ExecutionContext.of(txId));
        } catch (RuntimeException e) {
            return errorResult(e);
        }
    }

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
            return planExecutor.execute(plan, ExecutionContext.of(txId, session.getId()));
        } catch (RuntimeException e) {
            ErrorInfo error = toErrorInfo(e);
            if (error.code() == ErrorCode.TRANSACTION_ABORTED) {
                // CCM already aborted the transaction; the session falls back to auto-commit.
                session.clearTransaction();
            }
            return errorResult(error);
        }
    }

    private QueryResult executeInImplicitTransaction(SessionState session, QueryPlan plan) {
        TransactionResponse begin = requireStatus(concurrencyControl.beginTransaction(), TransactionStatus.ACTIVE);
        long txId = begin.txId();
        session.attachTransaction(txId, false);
        try {
            QueryResult result = planExecutor.execute(plan, ExecutionContext.of(txId, session.getId()));
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
            // TODO(M2): log abort failure; the original error is reported to the client.
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
