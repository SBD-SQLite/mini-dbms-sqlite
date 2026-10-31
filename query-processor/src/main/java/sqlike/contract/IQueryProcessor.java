package sqlike.contract;
import sqlike.shared.QueryPlan;
import sqlike.shared.QueryResult;
import sqlike.shared.Session;

public interface IQueryProcessor {
    void initialize();

    QueryResult execute(String sql, Session session);

    QueryResult executePlan(QueryPlan plan, long txId);
}