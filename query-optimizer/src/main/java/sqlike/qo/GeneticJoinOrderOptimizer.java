package sqlike.qo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.UnaryOperator;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;

/** QO-18: permutation GA with tournament selection, ordered crossover, swap mutation and elitism. */
public final class GeneticJoinOrderOptimizer {
    private final GeneticOptimizerConfig config;

    public GeneticJoinOrderOptimizer(GeneticOptimizerConfig config) {
        this.config = Objects.requireNonNull(config, "genetic configuration is required");
    }

    /** Pure join search. The QO5 pipeline additionally supplies access-path choice before fitness. */
    public Result optimize(PlanNode plan, CostModel cost) {
        return optimize(plan, cost, QO5Plans::simplifyIdentityProjects);
    }

    Result optimize(PlanNode plan, CostModel cost, UnaryOperator<PlanNode> prepare) {
        QO5Plans.requirePlan(plan);
        if (cost == null || prepare == null) throw new IllegalArgumentException("cost and plan preparation are required");
        return cost.withCatalogCache(() -> new Run(cost, prepare).optimize(plan));
    }

    /** No state is retained between calls; history includes initialization and each completed generation. */
    public record Result(PlanNode plan, double estimatedCost, int evaluatedIndividuals, int generationsRun,
                         List<Double> bestCosts) {
        public Result { bestCosts = List.copyOf(bestCosts); }
    }

    record Individual(List<Integer> order, JoinSearchSpace.Shape shape) {
        Individual {
            order = List.copyOf(order);
            Objects.requireNonNull(shape, "join shape is required");
            boolean[] seen = new boolean[order.size()];
            for (int gene : order) {
                if (gene < 0 || gene >= order.size() || seen[gene])
                    throw new IllegalArgumentException("individual must be a permutation");
                seen[gene] = true;
            }
        }
    }

    /** Ordered crossover (OX): one segment from the first parent, remaining genes in second-parent order. */
    static Individual crossover(Individual first, Individual second, Random random) {
        int size = first.order().size();
        if (size == 0 || second.order().size() != size) throw new IllegalArgumentException("parent sizes must match");
        int a = random.nextInt(size), b = random.nextInt(size);
        int from = Math.min(a, b), to = Math.max(a, b);
        List<Integer> child = new ArrayList<>(Collections.nCopies(size, -1));
        boolean[] used = new boolean[size];
        for (int i = from; i <= to; i++) {
            int gene = first.order().get(i);
            child.set(i, gene);
            used[gene] = true;
        }
        int destination = (to + 1) % size;
        for (int offset = 0; offset < size; offset++) {
            int gene = second.order().get((to + 1 + offset) % size);
            if (!used[gene]) {
                child.set(destination, gene);
                destination = (destination + 1) % size;
                used[gene] = true;
            }
        }
        return new Individual(child, random.nextBoolean() ? first.shape() : second.shape());
    }

    static Individual mutate(Individual individual, Random random) {
        List<Integer> order = new ArrayList<>(individual.order());
        if (order.size() > 1) {
            int first = random.nextInt(order.size()), second = random.nextInt(order.size() - 1);
            if (second >= first) second++;
            Collections.swap(order, first, second);
        }
        JoinSearchSpace.Shape[] shapes = JoinSearchSpace.Shape.values();
        return new Individual(order, shapes[random.nextInt(shapes.length)]);
    }

    private record Scored(Individual individual, PlanNode rawPlan, double cost) { }

    private final class Run {
        private final Random random = new Random(config.seed());
        private final CostModel cost;
        private final UnaryOperator<PlanNode> prepare;
        private final List<Double> history = new ArrayList<>();
        private int evaluated, generations;

        Run(CostModel cost, UnaryOperator<PlanNode> prepare) { this.cost = cost; this.prepare = prepare; }

        Result optimize(PlanNode original) {
            PlanNode raw = search(original, UnaryOperator.identity());
            PlanNode result = prepare.apply(raw);
            double finalCost = checkedCost(result);
            if (!result.outputColumns().equals(original.outputColumns()))
                throw QoErrors.invalidState("genetic search changed the output schema");
            if (history.isEmpty()) history.add(finalCost);
            return new Result(result, finalCost, evaluated, generations, history);
        }

        private PlanNode search(PlanNode node, UnaryOperator<PlanNode> wrap) {
            if (node.kind() == PlanNodeKind.LIMIT || QO5Plans.hasSubquery(node)) return node;
            var region = JoinSearchSpace.from(node);
            if (region.isPresent()) return evolve(node, region.get(), wrap);
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
                children.set(index, child);
                current = QO5Plans.withChildren(current, children);
            }
            return current;
        }

        private PlanNode evolve(PlanNode original, JoinSearchSpace space, UnaryOperator<PlanNode> wrap) {
            Map<Individual, Scored> cache = new HashMap<>();
            List<Individual> population = new ArrayList<>();
            for (int i = 0; i < config.populationSize(); i++) {
                List<Integer> order = new ArrayList<>(space.originalOrder());
                if (i == 1) Collections.reverse(order);
                else if (i > 2) Collections.shuffle(order, random);
                population.add(new Individual(order, JoinSearchSpace.Shape.values()[i % 3]));
            }
            PlanNode best = original;
            double bestCost = checkedCost(prepare.apply(wrap.apply(original)));
            for (int generation = 0; generation <= config.generations(); generation++) {
                List<Scored> scored = new ArrayList<>();
                for (Individual individual : population) {
                    Scored candidate = cache.computeIfAbsent(individual, key -> {
                        PlanNode raw = space.decode(key.order(), key.shape());
                        double estimate = checkedCost(prepare.apply(wrap.apply(raw)));
                        evaluated++;
                        return new Scored(key, raw, estimate);
                    });
                    scored.add(candidate);
                    if (candidate.cost() < bestCost) {
                        bestCost = candidate.cost();
                        best = candidate.rawPlan();
                    }
                }
                history.add(bestCost);
                if (generation == config.generations()) break;
                scored.sort(Comparator.comparingDouble(Scored::cost));
                List<Individual> next = new ArrayList<>();
                next.add(scored.get(0).individual()); // elitism
                while (next.size() < config.populationSize()) {
                    Individual child = crossover(tournament(scored).individual(), tournament(scored).individual(), random);
                    if (random.nextDouble() < config.mutationRate()) child = mutate(child, random);
                    // Duplicates are valid. Requiring uniqueness could loop forever on small joins.
                    next.add(child);
                }
                population = next;
                generations++;
            }
            return best;
        }

        private Scored tournament(List<Scored> population) {
            Scored best = population.get(random.nextInt(population.size()));
            for (int i = 1; i < 3; i++) {
                Scored candidate = population.get(random.nextInt(population.size()));
                if (candidate.cost() < best.cost()) best = candidate;
            }
            return best;
        }

        private double checkedCost(PlanNode plan) {
            PlanNodes.validate(plan);
            double estimate = cost.getCost(plan);
            if (!Double.isFinite(estimate) || estimate < 0)
                throw QoErrors.invalidState("genetic fitness cost must be finite and nonnegative");
            return estimate;
        }
    }
}
