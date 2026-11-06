package sqlike.qo;

import sqlike.contract.IQueryOptimizer;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

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
        if (isPassThrough(bound)) return new QueryPlan(bound, 0.0);
        PlanNode rewritten = selectionProjectionRewriter.rewrite(bound);
        PlanNode best = optimizer.optimize(rewritten);
        return new QueryPlan(best, costModel.getCost(best));
    }

    @Override
    public double getCost(QueryPlan plan) {
        if (plan == null) throw QoErrors.invalidValue("QueryPlan is null", 0);
        if (plan.root() != null && isPassThrough(plan.root())) return 0.0;
        return costModel.getCost(plan.root());
    }

    /** Statements that are validated but not optimized: no I/O cost model applies, so getCost is safe to call on them */
    private static boolean isPassThrough(PlanNode root) {
        return switch (root.kind()) {
            case CREATE_TABLE, DROP_TABLE -> true;
            default -> false;
        };
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
