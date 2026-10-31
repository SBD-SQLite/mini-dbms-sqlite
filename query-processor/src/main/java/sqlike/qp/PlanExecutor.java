package sqlike.qp;

import sqlike.shared.QueryPlan;
import sqlike.shared.QueryResult;


public interface PlanExecutor {
    QueryResult execute(QueryPlan plan, ExecutionContext context);
}
