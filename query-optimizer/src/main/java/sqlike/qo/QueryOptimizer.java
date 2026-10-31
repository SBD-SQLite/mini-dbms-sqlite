package sqlike.qo;

import sqlike.contract.IQueryOptimizer;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

/**
 * QO facade (QO-01, QO-15, QO-16). Hidden internal class: QP only sees {@link IQueryOptimizer}.
 * Flow: parseQuery = Lexer -> QueryParser; optimizeQuery = Binder.bind -> JoinAccessPathOptimizer.optimize
 * -> QueryPlan(root, CostModel.getCost). Stateless, so it is safe for concurrent QP clients.
 * Errors are thrown as {@link QoException} (carries ErrorInfo); QP turns them into QueryResult.ERROR.
 *
 * Milestone 1: the facade wiring and parsing of SELECT/transactions are real; Binder, CostModel and
 * JoinAccessPathOptimizer (QO3/QO5) and ExpressionParser/StatementParser (QO2) are MOCK placeholders.
 */
public class QueryOptimizer implements IQueryOptimizer {

    private final Lexer lexer;
    private final QueryParser queryParser;
    private final Binder binder;
    private final SelectionProjectionRewriter selectionProjectionRewriter;
    private final JoinAccessPathOptimizer optimizer;
    private final CostModel costModel;
    private final PlanPrinter printer;

    public QueryOptimizer(IStorageManager storage) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        ExpressionParser expr = new ExpressionParser();
        this.lexer = new Lexer();
        this.queryParser = new QueryParser(new SelectParser(expr), new StatementParser());
        this.binder = new Binder(storage);
        this.selectionProjectionRewriter = new SelectionProjectionRewriter();
        this.optimizer = new JoinAccessPathOptimizer();
        this.costModel = new CostModel(storage);
        this.printer = new PlanPrinter();
    }

    @Override
    public ParsedQuery parseQuery(String sql) {
        if (sql == null || sql.isBlank()) throw QoErrors.syntax("empty statement", 0);
        TokenStream tokens = lexer.tokenize(sql);
        return queryParser.parse(tokens, sql);
    }

    @Override
    public QueryPlan optimizeQuery(ParsedQuery query) {
        if (query == null) throw QoErrors.invalidValue("ParsedQuery is null", 0);
        StatementType st = query.statementType();
        if (st == StatementType.BEGIN || st == StatementType.COMMIT || st == StatementType.ABORT)
            throw QoErrors.invalidState("transaction statements are not optimized (" + st + ")");
        PlanNode bound = binder.bind(query);
        PlanNode rewritten = selectionProjectionRewriter.rewrite(bound);
        PlanNode best = optimizer.optimize(rewritten);
        return new QueryPlan(best, costModel.getCost(best));
    }

    @Override
    public double getCost(QueryPlan plan) {
        if (plan == null) throw QoErrors.invalidValue("QueryPlan is null", 0);
        return costModel.getCost(plan.root());
    }

    /** Extra (not in IQueryOptimizer): human-readable before/after plan for the driver / QO-15. */
    public String explainQuery(String sql) {
        ParsedQuery parsed = parseQuery(sql);
        if (parsed.transactionCommand().isPresent())
            return "TRANSACTION " + parsed.transactionCommand().get().action() + " (not optimized)\n";
        QueryPlan plan = optimizeQuery(parsed);
        return printer.printBeforeAfter(parsed.root().get(), plan.root(), plan.estimatedCost());
    }
}
