package sqlike.qo;

import java.io.PrintStream;
import java.util.Locale;
import sqlike.qo.QO5Test.QO5GeneticTest;
import sqlike.qo.QO5Test.QO5JoinSearchBenchmark;
import sqlike.qo.QO5Test.QO5BenchmarkCatalog;
import sqlike.contract.IStorageManager;
import sqlike.shared.PlanNode;
import sqlike.shared.QoException;

/** Standalone QO5 comparison; uses metadata-only catalogs and does not execute SQL or edit QO1's driver. */
public final class QO5GeneticDriver {
    private QO5GeneticDriver() { }

    public static void main(String[] args) {
        try { run(args, System.out); }
        catch (IllegalArgumentException | QoException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    record Options(GeneticOptimizerConfig config, int bufferBlocks, String sql, boolean benchmark,
                   Long statisticsSeed, boolean indexed) {
        static Options parse(String[] args) {
            GeneticOptimizerConfig defaults = GeneticOptimizerConfig.defaults();
            int population = defaults.populationSize(), generations = defaults.generations(), buffers = 3;
            double mutation = defaults.mutationRate();
            long seed = defaults.seed();
            String sql = null;
            Long statisticsSeed = null;
            boolean benchmark = false, indexed = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--benchmark")) { benchmark = true; continue; }
                if (args[i].equals("--indexed")) { indexed = true; continue; }
                String option = args[i];
                if (++i >= args.length) throw new IllegalArgumentException("missing value for " + option);
                String value = args[i];
                switch (option) {
                    case "--population" -> population = Integer.parseInt(value);
                    case "--generations" -> generations = Integer.parseInt(value);
                    case "--mutation-rate" -> mutation = Double.parseDouble(value);
                    case "--seed" -> seed = Long.parseLong(value);
                    case "--buffer-blocks" -> buffers = Integer.parseInt(value);
                    case "--sql" -> sql = value;
                    case "--statistics-seed" -> statisticsSeed = Long.parseLong(value);
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            if (buffers < 3) throw new IllegalArgumentException("buffer-blocks must be at least 3");
            if (benchmark && sql != null) throw new IllegalArgumentException("--sql cannot be combined with --benchmark");
            if (!benchmark && (statisticsSeed != null || indexed))
                throw new IllegalArgumentException("--statistics-seed and --indexed require --benchmark");
            return new Options(new GeneticOptimizerConfig(population, generations, mutation, seed), buffers, sql,
                    benchmark, statisticsSeed, indexed);
        }
    }

    static void run(String[] args, PrintStream out) {
        Options options = Options.parse(args);
        out.println("GA configuration: " + options.config());
        out.println("Buffer blocks: " + options.bufferBlocks());
        out.println("Heuristic (128) is independent of DP. All methods use the same statistics, access paths and CostModel.");
        out.println("Costs are estimated I/O milliseconds; optimize_ms is local search time, not SQL execution time.");
        out.println("Counters differ: heuristic complete plans; DP partial/full candidates; GA unique chromosomes.");
        if (options.benchmark()) {
            out.println("Illustrative synthetic fixtures; GA is not guaranteed to beat heuristic or DP.");
            for (int tables : new int[] { 5, 6 }) {
                long statisticsSeed = options.statisticsSeed() == null ? QO5BenchmarkCatalog.defaultSeed(tables) : options.statisticsSeed();
                var scenario = QO5BenchmarkCatalog.scenario(tables, statisticsSeed, options.indexed());
                out.printf(Locale.ROOT, "%nCase: %d tables; statisticsSeed=%d; indexed=%s%n", tables, statisticsSeed, options.indexed());
                scenario.statistics().forEach((table, stats) -> out.printf(Locale.ROOT,
                        "%s: rows=%d blocks=%d recordSize=%.1f blockingFactor=%d V(id)=%d V(k)=%d%n", table,
                        stats.recordCount(), stats.blockCount(), stats.averageRecordSize(), stats.blockingFactor(),
                        stats.distinctCounts().get("id"), stats.distinctCounts().get("k")));
                printComparison(scenario.sql(), scenario.storage(), options, out);
            }
        } else {
            printComparison(options.sql() == null ? QO5GeneticTest.FOUR + " WHERE s.id = 3" : options.sql(),
                    QO5GeneticTest.indexedCatalog(), options, out);
        }
    }

    private static void printComparison(String sql, IStorageManager storage, Options options, PrintStream out) {
        var query = new QueryOptimizer(storage).parseQuery(sql);
        PlanNode bound = new Binder(storage).bind(query);
        PlanNode rewritten = new SelectionProjectionRewriter(new CardinalityEstimator(storage)).rewrite(bound);
        var report = QO5JoinSearchBenchmark.compare(rewritten, storage, options.bufferBlocks(), options.config());
        out.println("SQL: " + sql);
        out.println("method | estimated_cost_ms | optimize_ms | candidates");
        for (var measurement : report.measurements()) out.printf(Locale.ROOT, "%s | %.6f | %.3f | %d%n",
                measurement.method(), measurement.cost(), measurement.elapsedNanos() / 1_000_000.0, measurement.candidates());
        double heuristic = report.measurements().get(0).cost(), genetic = report.measurements().get(2).cost();
        if (heuristic > 0) out.printf(Locale.ROOT, "GA improvement vs heuristic: %.2f%%%n", 100 * (heuristic - genetic) / heuristic);
        out.printf(Locale.ROOT, "Selected estimated cost: %.6f ms%n", report.selectedCost());
        out.print(new PlanPrinter().print(report.selected()));
    }
}
