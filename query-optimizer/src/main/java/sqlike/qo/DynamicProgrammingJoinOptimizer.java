package sqlike.qo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import sqlike.shared.PlanNode;

/**
 * Bounded subset DP over ordered partitions (including bushy joins). Several alternatives are retained
 * per subset because QO3 estimates can depend on tree shape/layout. This is not a global-optimality claim.
 * Expression-owned subquery scopes are visited separately by the QO5 pipeline.
 */
public final class DynamicProgrammingJoinOptimizer {
    private final int maxRelations;
    private final int plansPerSubset;
    private final int maxEvaluations;

    public DynamicProgrammingJoinOptimizer() { this(6, 4, 20_000); }

    public DynamicProgrammingJoinOptimizer(int maxRelations, int plansPerSubset, int maxEvaluations) {
        if (maxRelations < 2 || maxRelations > 12)
            throw new IllegalArgumentException("maxRelations must be between 2 and 12");
        if (plansPerSubset < 1 || plansPerSubset > 32)
            throw new IllegalArgumentException("plansPerSubset must be between 1 and 32");
        if (maxEvaluations < 2) throw new IllegalArgumentException("maxEvaluations must be at least 2");
        this.maxRelations = maxRelations;
        this.plansPerSubset = plansPerSubset;
        this.maxEvaluations = maxEvaluations;
    }

    /** Join-only use. The full QO5 pipeline also supplies its existing access-path chooser. */
    public Result optimize(PlanNode plan, CostModel cost) {
        return optimize(plan, cost, ProjectionCleanup::normalize);
    }

    Result optimize(PlanNode plan, CostModel cost, UnaryOperator<PlanNode> prepare) {
        QO5Plans.requirePlan(plan);
        if (cost == null || prepare == null) throw new IllegalArgumentException("cost and plan preparation are required");
        return cost.withCatalogCache(() -> new Run(cost, prepare).optimize(plan));
    }

    /** Counters/budget are local to a call; skipped regions retain the incoming fallback plan. */
    public record Result(PlanNode plan, double estimatedCost, int evaluatedCandidates,
                         int optimizedRegions, int skippedRegions, boolean budgetExhausted) { }

    private record Candidate(PlanNode plan, double cost) { }

    private final class Run {
        private final CostModel cost;
        private final UnaryOperator<PlanNode> prepare;
        private int evaluations, optimized, skipped;
        private boolean exhausted;

        Run(CostModel cost, UnaryOperator<PlanNode> prepare) { this.cost = cost; this.prepare = prepare; }

        Result optimize(PlanNode original) {
            PlanNode baseline = prepare.apply(original);
            PlanNode searched = prepare.apply(search(original, UnaryOperator.identity()));
            PlanNode result = checkedCost(searched) < checkedCost(baseline) ? searched : baseline;
            if (!result.outputColumns().equals(original.outputColumns()))
                throw QoErrors.invalidState("DP changed the output schema");
            return new Result(result, checkedCost(result), evaluations, optimized, skipped, exhausted);
        }

        private PlanNode search(PlanNode node, UnaryOperator<PlanNode> wrap) {
            var region = JoinSearchSpace.from(node);
            if (region.isPresent()) {
                if (region.get().size() > maxRelations || evaluations >= maxEvaluations) {
                    skipped++;
                    if (evaluations >= maxEvaluations) exhausted = true;
                    return node;
                }
                return solve(node, region.get(), wrap);
            }
            PlanNode current = node;
            for (int i = 0; i < current.children().size(); i++) {
                PlanNode parent = current;
                int index = i;
                PlanNode child = search(parent.children().get(index), replacement -> {
                    List<PlanNode> children = new ArrayList<>(parent.children());
                    children.set(index, replacement);
                    return wrap.apply(QO5Plans.withChildren(parent, children));
                });
                List<PlanNode> children = new ArrayList<>(current.children());
                children.set(i, child);
                current = QO5Plans.withChildren(current, children);
            }
            return current;
        }

        private PlanNode solve(PlanNode original, JoinSearchSpace space, UnaryOperator<PlanNode> wrap) {
            Map<Integer, List<Candidate>> table = new HashMap<>();
            int full = (1 << space.size()) - 1;
            for (int i = 0; i < space.size(); i++) {
                if (!reserveEvaluation()) { skipped++; return original; }
                PlanNode leaf = prepare.apply(space.input(i));
                table.put(1 << i, List.of(new Candidate(leaf, checkedCost(leaf))));
            }
            PlanNode best = original;
            double bestCost = checkedCost(prepare.apply(wrap.apply(original)));
            boolean complete = true;
            search:
            for (int size = 2; size <= space.size(); size++) {
                for (int mask = 1; mask <= full; mask++) {
                    if (Integer.bitCount(mask) != size) continue;
                    List<Candidate> beam = new ArrayList<>();
                    // Both orientations are intentional: BNLJ cost depends on the outer input.
                    for (int left = (mask - 1) & mask; left != 0; left = (left - 1) & mask) {
                        int right = mask ^ left;
                        for (Candidate a : table.get(left)) for (Candidate b : table.get(right)) {
                            if (!reserveEvaluation()) { complete = false; break search; }
                            PlanNode joined = space.combine(a.plan(), b.plan());
                            PlanNode candidate = mask == full ? prepare.apply(wrap.apply(space.finish(joined))) : joined;
                            double estimate = checkedCost(candidate);
                            if (mask == full) {
                                if (estimate < bestCost) {
                                    bestCost = estimate;
                                    best = space.finish(joined);
                                }
                            } else retain(beam, new Candidate(candidate, estimate));
                        }
                    }
                    if (mask != full) table.put(mask, List.copyOf(beam));
                }
            }
            if (complete) optimized++; else skipped++;
            // A budget cut may have found complete plans already; partial subsets never escape.
            return best;
        }

        private void retain(List<Candidate> beam, Candidate candidate) {
            if (beam.stream().anyMatch(existing -> existing.plan().equals(candidate.plan()))) return;
            beam.add(candidate);
            beam.sort(Comparator.comparingDouble(Candidate::cost)); // stable ties retain discovery order
            if (beam.size() > plansPerSubset) beam.remove(beam.size() - 1);
        }

        private boolean reserveEvaluation() {
            if (evaluations >= maxEvaluations) { exhausted = true; return false; }
            evaluations++;
            return true;
        }

        private double checkedCost(PlanNode plan) {
            PlanNodes.validate(plan);
            double estimate = cost.getCost(plan);
            if (!Double.isFinite(estimate) || estimate < 0)
                throw QoErrors.invalidState("DP cost must be finite and nonnegative");
            return estimate;
        }
    }
}
