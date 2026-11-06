package sqlike.contract;
import sqlike.shared.ParsedQuery;
import sqlike.shared.QueryPlan;

public interface IQueryOptimizer {
    /** Lex and parse SQL into a ParsedQuery */
    ParsedQuery parseQuery(String sql);

    /** Bind, rewrite, and choose the cheapest candidate plan, but not valid for BEGIN/COMMIT/ABORT */
    QueryPlan optimizeQuery(ParsedQuery query);

    /** Estimate plan cost from statistics and algorithms available in QP */
    double getCost(QueryPlan plan);
}
