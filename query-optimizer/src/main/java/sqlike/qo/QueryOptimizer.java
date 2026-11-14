package sqlike.qo;

import sqlike.contract.IQueryOptimizer;
import sqlike.contract.IStorageManager;
import java.util.Optional;
import sqlike.shared.*;

public class QueryOptimizer implements IQueryOptimizer {

    private final Lexer lexer;
    private final QueryParser queryParser;
    private final Binder binder;
    private final SelectionProjectionRewriter selectionProjectionRewriter;
    private final JoinAccessPathOptimizer optimizer;
    private final CostModel costModel;
    private final PlanPrinter printer;
    private final PlanAnnotator annotator;
    private final IStorageManager storage;
    private final DdlValidator ddlValidator;

    public QueryOptimizer(IStorageManager storage) {
        this(storage, 3);
    }

    public QueryOptimizer(IStorageManager storage, int bufferBlocks) {
        this(storage, bufferBlocks, null);
    }

    /**
     * Opt-in genetic join search: with a non-null {@code geneticConfig} the cheaper of the heuristic and the GA plan
     * is returned. A null config keeps the default heuristic behavior exactly.
     */
    public QueryOptimizer(IStorageManager storage, int bufferBlocks, GeneticOptimizerConfig geneticConfig) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        ExpressionParser expr = new ExpressionParser();
        this.lexer = new Lexer();
        this.queryParser = new QueryParser(new SelectParser(expr), new StatementParser());
        this.storage = storage;
        this.ddlValidator = new DdlValidator();
        this.binder = new Binder(storage);
        CardinalityEstimator cardinalityEstimator = new CardinalityEstimator(storage);
        this.selectionProjectionRewriter = new SelectionProjectionRewriter(cardinalityEstimator);
        this.costModel = new CostModel(storage, bufferBlocks);
        this.optimizer = geneticConfig == null
                ? new JoinAccessPathOptimizer(costModel, storage)
                : new JoinAccessPathOptimizer(costModel, storage, geneticConfig);
        this.printer = new PlanPrinter();
        this.annotator = new PlanAnnotator(cardinalityEstimator);
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
        // CREATE/DROP TABLE: validated against the catalog by DdlValidator (the Binder does not bind DDL), then passed through at cost 0.
        Optional<PlanNode> ddl = query.root().filter(QueryOptimizer::isPassThrough);
        if (ddl.isPresent()) return new QueryPlan(ddlValidator.validate(ddl.get(), storage), 0.0);
        PlanNode bound = binder.bind(query);
        if (isPassThrough(bound)) return new QueryPlan(bound, 0.0);
        PlanNode rewritten = selectionProjectionRewriter.rewrite(bound);
        return costModel.withCatalogCache(() -> {
            PlanNode best = optimizer.optimize(rewritten);
            return new QueryPlan(best, costModel.getCost(best));
        });
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

    /**
     * Extra: heuristic and (when configured) GA cost of the join search for one SELECT, using the same rewrites as
     * {@link #optimizeQuery}. Only SELECT is accepted (INVALID_STATE otherwise).
     */
    public JoinAccessPathOptimizer.OptimizationComparison compareJoinSearch(String sql) {
        ParsedQuery parsed = parseQuery(sql);
        if (parsed.statementType() != StatementType.SELECT)
            throw QoErrors.invalidState("join search comparison needs a SELECT, got " + parsed.statementType());
        PlanNode rewritten = selectionProjectionRewriter.rewrite(binder.bind(parsed));
        return optimizer.optimizeWithComparison(rewritten);
    }

    /** Extra: human-readable before/after plan buat driver */
    public String explainQuery(String sql) {
        ParsedQuery parsed = parseQuery(sql);
        if (parsed.transactionCommand().isPresent())
            return "TRANSACTION " + parsed.transactionCommand().get().action() + " (not optimized)\n";
        QueryPlan plan = optimizeQuery(parsed);
        if (isPassThrough(plan.root())) return printer.printNotOptimized(plan.root(), plan.estimatedCost());
        PlanNode bound = binder.bind(parsed);
        // "Before" is the Binder result (qualified, catalog-checked), not the raw parser tree.
        double costBefore = costModel.getCost(bound);
        return printer.printBeforeAfter(annotator.annotate(bound), costBefore,
                annotator.annotate(plan.root()), plan.estimatedCost());
    }
}
