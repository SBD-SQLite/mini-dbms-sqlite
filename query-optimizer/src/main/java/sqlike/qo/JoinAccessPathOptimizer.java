package sqlike.qo;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.OptionalDouble;
import sqlike.contract.IStorageManager;
import sqlike.shared.PlanNode;

/** QO-11 through QO-14: equivalent join/access alternatives and final QO3 cost comparison. */
public class JoinAccessPathOptimizer {
    private final JoinRewriter joinRewriter = new JoinRewriter();
    private final JoinOrderEnumerator joinEnumerator = new JoinOrderEnumerator();
    private final PlanSelector planSelector = new PlanSelector();
    private final CostModel costModel;
    private final AccessPathOptimizer accessOptimizer;
    private final GeneticJoinOrderOptimizer geneticOptimizer;

    /** Supply the same QO3 model used by the facade; no catalog or cost formula is duplicated. */
    public JoinAccessPathOptimizer(CostModel costModel) {
        if (costModel == null) throw new IllegalArgumentException("cost model is required");
        this.costModel = costModel;
        this.accessOptimizer = null;
        this.geneticOptimizer = null;
    }

    /** Full pipeline; the one-argument constructor remains available for join-only consumers. */
    public JoinAccessPathOptimizer(CostModel costModel, IStorageManager storage) {
        if (costModel == null) throw new IllegalArgumentException("cost model is required");
        this.costModel = costModel;
        this.accessOptimizer = new AccessPathOptimizer(storage);
        this.geneticOptimizer = null;
    }

    /** Opt-in GA; existing constructors and the IQueryOptimizer contract keep heuristic defaults. */
    public JoinAccessPathOptimizer(CostModel costModel, IStorageManager storage, GeneticOptimizerConfig config) {
        if (costModel == null) throw new IllegalArgumentException("cost model is required");
        this.costModel = costModel;
        this.accessOptimizer = new AccessPathOptimizer(storage);
        this.geneticOptimizer = new GeneticJoinOrderOptimizer(config);
    }

    public PlanNode optimize(PlanNode plan) {
        QO5Plans.requirePlan(plan);
        if (!isOptimizable(plan)) return plan;
        return optimizeWithComparison(plan).plan();
    }

    /** Per-call comparison; no last-result mutable state is retained on the optimizer. */
    public OptimizationComparison optimizeWithComparison(PlanNode plan) {
        QO5Plans.requirePlan(plan);
        return costModel.withCatalogCache(() -> compare(plan));
    }

    public record OptimizationComparison(PlanNode plan, double estimatedCost, double heuristicCost,
                                         OptionalDouble geneticCost, int evaluatedIndividuals) { }

    private OptimizationComparison compare(PlanNode plan) {
        if (!isOptimizable(plan)) {
            double cost = costModel.getCost(plan);
            return new OptimizationComparison(plan, cost, cost, OptionalDouble.empty(), 0);
        }

        PlanNode rewritten = joinRewriter.mergeSelectionIntoJoin(joinRewriter.cartesianToJoin(plan));
        var candidates = new LinkedHashSet<PlanNode>();
        for (PlanNode candidate : generateCandidates(rewritten))
            candidates.add(QO5Plans.simplifyIdentityProjects(restoreOutputSchema(candidate, plan)));
        // Include the incoming baseline too; equivalent rewritten plans win equal-cost ties.
        candidates.add(QO5Plans.simplifyIdentityProjects(plan));
        PlanNode candidate = planSelector.pickCheapest(List.copyOf(candidates), costModel);
        PlanNode result = QO5Plans.simplifyIdentityProjects(restoreOutputSchema(candidate, plan));
        PlanNodes.validate(result);
        double heuristicCost = costModel.getCost(result);
        if (geneticOptimizer == null)
            return new OptimizationComparison(result, heuristicCost, heuristicCost, OptionalDouble.empty(), 0);

        GeneticJoinOrderOptimizer.Result genetic = geneticOptimizer.optimize(rewritten, costModel,
                raw -> prepareGeneticCandidate(raw, plan));
        PlanNode selected = planSelector.pickCheapest(List.of(result, genetic.plan()), costModel);
        return new OptimizationComparison(selected, costModel.getCost(selected), heuristicCost,
                OptionalDouble.of(genetic.estimatedCost()), genetic.evaluatedIndividuals());
    }

    private PlanNode prepareGeneticCandidate(PlanNode candidate, PlanNode original) {
        PlanNode accessed = accessOptimizer == null ? candidate : accessOptimizer.chooseAccessPath(candidate, costModel);
        return QO5Plans.simplifyIdentityProjects(restoreOutputSchema(accessed, original));
    }

    private List<PlanNode> generateCandidates(PlanNode plan) {
        var candidates = new LinkedHashSet<PlanNode>();
        for (PlanNode joinCandidate : joinEnumerator.enumerate(plan)) {
            candidates.add(joinCandidate);
            if (accessOptimizer != null)
                candidates.add(accessOptimizer.chooseAccessPath(joinCandidate, costModel));
        }
        return List.copyOf(candidates);
    }

    private PlanNode restoreOutputSchema(PlanNode candidate, PlanNode original) {
        return QO5Plans.restore(candidate, original);
    }

    private boolean isOptimizable(PlanNode plan) {
        return switch (plan.kind()) {
            case SCAN, INDEX_SCAN, FILTER, PROJECT, JOIN, CARTESIAN, SORT, LIMIT -> true;
            case UPDATE, DELETE -> accessOptimizer != null;
            case INSERT, CREATE_INDEX, CREATE_TABLE, DROP_TABLE -> false;
        };
    }
}
