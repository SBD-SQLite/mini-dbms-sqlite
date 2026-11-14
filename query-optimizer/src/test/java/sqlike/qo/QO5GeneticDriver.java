package sqlike.qo;

import java.util.Locale;
import sqlike.shared.PlanNode;
import sqlike.shared.QoException;

/** Standalone QO5 cost comparison using a mock catalog; does not execute SQL or change QO1's driver. */
public final class QO5GeneticDriver {
    private QO5GeneticDriver() { }

    public static void main(String[] args) {
        try {
            GeneticOptimizerConfig defaults = GeneticOptimizerConfig.defaults();
            int population = defaults.populationSize(), generations = defaults.generations();
            double mutation = defaults.mutationRate();
            long seed = defaults.seed();
            String sql = QO5GeneticTest.FOUR + " WHERE s.id = 3";
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + args[i]);
                String value = args[i + 1];
                switch (args[i]) {
                    case "--population" -> population = Integer.parseInt(value);
                    case "--generations" -> generations = Integer.parseInt(value);
                    case "--mutation-rate" -> mutation = Double.parseDouble(value);
                    case "--seed" -> seed = Long.parseLong(value);
                    case "--sql" -> sql = value;
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            GeneticOptimizerConfig config = new GeneticOptimizerConfig(population, generations, mutation, seed);
            var storage = QO5GeneticTest.indexedCatalog();
            CostModel cost = new CostModel(storage);
            var query = new QueryOptimizer(storage).parseQuery(sql);
            PlanNode bound = new Binder(storage).bind(query);
            PlanNode rewritten = new SelectionProjectionRewriter().rewrite(bound);
            var report = new JoinAccessPathOptimizer(cost, storage, config).optimizeWithComparison(rewritten);
            System.out.println("GA configuration: " + config);
            System.out.printf(Locale.ROOT, "Heuristic cost: %.6f ms%n", report.heuristicCost());
            if (report.geneticCost().isPresent())
                System.out.printf(Locale.ROOT, "GA cost: %.6f ms%n", report.geneticCost().getAsDouble());
            System.out.printf(Locale.ROOT, "Selected cost: %.6f ms%n", report.estimatedCost());
            System.out.println("Evaluated individuals: " + report.evaluatedIndividuals());
            System.out.print(new PlanPrinter().print(report.plan()));
        } catch (IllegalArgumentException | QoException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }
}
