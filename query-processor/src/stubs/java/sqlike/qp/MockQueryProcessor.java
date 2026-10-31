package sqlike.qp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sqlike.contract.IConcurrencyControlManager;
import sqlike.contract.IFailureRecoveryManager;
import sqlike.contract.IQueryProcessor;
import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.QueryPlan;
import sqlike.shared.QueryResult;
import sqlike.shared.RecoveryResult;
import sqlike.shared.ResultKind;
import sqlike.shared.Session;

/** M1 fixture-based implementation; SQL and plans return only explicitly registered responses. */
public class MockQueryProcessor implements IQueryProcessor {
    private final IFailureRecoveryManager recovery;
    private final IConcurrencyControlManager concurrency;
    private final Map<String, QueryResult> sqlResults;
    private final Map<PlanRequest, QueryResult> planResults;
    private boolean initialized;

    public MockQueryProcessor(IFailureRecoveryManager recovery, IConcurrencyControlManager concurrency) {
        this.recovery = Objects.requireNonNull(recovery);
        this.concurrency = Objects.requireNonNull(concurrency);
        sqlResults = new HashMap<>();
        planResults = new HashMap<>();
    }

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void initialize() {
        initialized = false;
        RecoveryResult result = Objects.requireNonNull(recovery.recover());
        concurrency.initAfterRecovery(result.lastTxId());
        initialized = true;
    }

    @Override
    public QueryResult execute(String sql, Session session) {
        if (!initialized) {
            return error(ErrorCode.INVALID_STATE, "QP mock has not completed initialization");
        }
        if (sql == null || sql.isBlank() || session == null || session.sessionId() == null || session.sessionId().isBlank()
                || session.activeTxId().filter(txId -> txId <= 0).isPresent()) {
            return error(ErrorCode.INVALID_VALUE, "A SQL statement and valid session are required");
        }
        return sqlResults.getOrDefault(sql, error(ErrorCode.UNSUPPORTED_OPERATION, "SQL fixture is not registered"));
    }

    @Override
    public QueryResult executePlan(QueryPlan plan, long txId) {
        if (!initialized) {
            return error(ErrorCode.INVALID_STATE, "QP mock has not completed initialization");
        }
        if (plan == null || plan.root() == null || txId <= 0) {
            return error(ErrorCode.INVALID_VALUE, "A plan and CCM transaction ID are required");
        }
        return planResults.getOrDefault(new PlanRequest(plan, txId),
                error(ErrorCode.UNSUPPORTED_OPERATION, "Plan fixture is not registered for this transaction"));
    }

    public void addSqlResult(String sql, QueryResult result) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL fixture cannot be blank");
        }
        sqlResults.put(sql, Objects.requireNonNull(result));
    }

    public void addPlanResult(QueryPlan plan, long txId, QueryResult result) {
        if (plan == null || plan.root() == null || txId <= 0) {
            throw new IllegalArgumentException("Plan fixture requires a plan and positive transaction ID");
        }
        planResults.put(new PlanRequest(plan, txId), Objects.requireNonNull(result));
    }

    private QueryResult error(ErrorCode code, String message) {
        ErrorInfo error = new ErrorInfo(code, ComponentType.QP, message, Optional.empty());
        return new QueryResult(ResultKind.ERROR, List.of(), List.of(), 0, message, Optional.of(error));
    }

    private record PlanRequest(QueryPlan plan, long txId) {}
}
