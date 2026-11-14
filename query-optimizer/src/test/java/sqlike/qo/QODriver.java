package sqlike.qo;

import java.util.Scanner;
import java.util.function.IntSupplier;
import sqlike.shared.ParsedQuery;
import sqlike.shared.QoException;

public final class QODriver {
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--test")) {
            if (runAllTests() != 0) System.exit(1);
            return;
        }
        if (args.length >= 1 && args[0].equals("--scenarios")) {
            java.nio.file.Path file = args.length > 1 ? java.nio.file.Path.of(args[1]) : QO1Test.DEFAULT_SCENARIOS;
            if (QO1Test.runScenarios(file, true) != 0) System.exit(1);
            return;
        }
        if (args.length >= 2 && (args[0].equals("--explain") || args[0].equals("--compare"))) {
            try {
                Options options = Options.parse(args, 2);
                QueryOptimizer optimizer = options.newOptimizer(MockStorageManager.create());
                if (args[0].equals("--explain")) System.out.print(optimizer.explainQuery(args[1]));
                else System.out.print(options.compare(optimizer, args[1]));
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage() + System.lineSeparator() + Options.USAGE);
                System.exit(2);
            } catch (QoException e) { System.out.println(e.getInfo().code() + e.getInfo().position().map(p -> " at " + p).orElse("") + ": " + e.getMessage()); System.exit(1); }
            return;
        }
        Scanner input = new Scanner(System.in);
        QueryParser parser = new QueryParser(new SelectParser(new ExpressionParser()), new StatementParser());
        while (true) {
            System.out.println("\n1. Parse SQL\n2. Run all tests\n3. Explain SQL (plan before/after + cost)\n4. Run scenario file\n0. Exit");
            System.out.print("> ");
            if (!input.hasNextLine()) return;
            switch (input.nextLine().trim()) {
                case "0" -> { return; }
                case "1" -> {
                    System.out.print("SQL (one statement): ");
                    if (!input.hasNextLine()) return;
                    String sql = input.nextLine();
                    try {
                        ParsedQuery parsed = parser.parse(new Lexer().tokenize(sql), sql);
                        System.out.println("Statement: " + parsed.statementType());
                        parsed.root().ifPresent(root -> System.out.print(new PlanPrinter().print(root)));
                        parsed.transactionCommand().ifPresent(command -> System.out.println("Transaction: " + command.action()));
                    } catch (QoException e) {
                        System.out.println(e.getInfo().code() + e.getInfo().position().map(p -> " at " + p).orElse("") + ": " + e.getMessage());
                    }
                }
                case "2" -> runAllTests();
                case "3" -> {
                    System.out.print("SQL (one statement): ");
                    if (!input.hasNextLine()) return;
                    try {
                        System.out.print(new QueryOptimizer(MockStorageManager.create()).explainQuery(input.nextLine()));
                    } catch (QoException e) {
                        System.out.println(e.getInfo().code() + e.getInfo().position().map(p -> " at " + p).orElse("") + ": " + e.getMessage());
                    }
                }
                case "4" -> {
                    System.out.print("Scenario file [" + QO1Test.DEFAULT_SCENARIOS + "]: ");
                    if (!input.hasNextLine()) return;
                    String path = input.nextLine().trim();
                    QO1Test.runScenarios(path.isEmpty() ? QO1Test.DEFAULT_SCENARIOS : java.nio.file.Path.of(path), true);
                }
                default -> System.out.println("Choose 0, 1, 2, 3, or 4.");
            }
        }
    }

    /** Command-line options shared by --explain and --compare: buffer blocks (M) and the opt-in genetic search. */
    record Options(int bufferBlocks, java.util.Optional<GeneticOptimizerConfig> genetic) {
        static final String USAGE = "options: [--buffer-blocks M (>= 3, default 3)] [--ga] [--population N] [--generations N] "
                + "[--mutation-rate X] [--seed N]   (any GA parameter turns the genetic search on; --compare always uses it)";

        static Options parse(String[] args, int from) {
            int bufferBlocks = 3;
            boolean ga = false;
            GeneticOptimizerConfig defaults = GeneticOptimizerConfig.defaults();
            int population = defaults.populationSize(), generations = defaults.generations();
            double mutation = defaults.mutationRate();
            long seed = defaults.seed();
            for (int i = from; i < args.length; i++) {
                String option = args[i];
                if (option.equals("--ga")) { ga = true; continue; }
                if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + option);
                String value = args[++i];
                try {
                    switch (option) {
                        case "--buffer-blocks" -> bufferBlocks = Integer.parseInt(value);
                        case "--population" -> { population = Integer.parseInt(value); ga = true; }
                        case "--generations" -> { generations = Integer.parseInt(value); ga = true; }
                        case "--mutation-rate" -> { mutation = Double.parseDouble(value); ga = true; }
                        case "--seed" -> { seed = Long.parseLong(value); ga = true; }
                        default -> throw new IllegalArgumentException("unknown option " + option);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid value '" + value + "' for " + option);
                }
            }
            if (bufferBlocks < 3) throw new IllegalArgumentException("--buffer-blocks must be at least 3");
            return new Options(bufferBlocks, ga ? java.util.Optional.of(new GeneticOptimizerConfig(population, generations, mutation, seed))
                    : java.util.Optional.empty());
        }

        QueryOptimizer newOptimizer(sqlike.contract.IStorageManager storage) {
            return new QueryOptimizer(storage, bufferBlocks, genetic.orElse(null));
        }

        String compare(QueryOptimizer optimizer, String sql) {
            if (genetic.isEmpty()) {
                Options withGa = withDefaultGenetic();
                return withGa.compare(withGa.newOptimizer(MockStorageManager.create()), sql);
            }
            var report = optimizer.compareJoinSearch(sql);
            StringBuilder out = new StringBuilder();
            out.append("buffer blocks (M): ").append(bufferBlocks).append(System.lineSeparator());
            out.append("GA configuration: ").append(genetic.get()).append(System.lineSeparator());
            out.append(String.format(java.util.Locale.ROOT, "Heuristic cost: %.6f ms%n", report.heuristicCost()));
            report.geneticCost().ifPresent(cost -> out.append(String.format(java.util.Locale.ROOT, "GA cost: %.6f ms%n", cost)));
            out.append(String.format(java.util.Locale.ROOT, "Selected cost: %.6f ms%n", report.estimatedCost()));
            out.append("Evaluated individuals: ").append(report.evaluatedIndividuals()).append(System.lineSeparator());
            out.append(new PlanPrinter().print(report.plan()));
            return out.toString();
        }

        private Options withDefaultGenetic() { return new Options(bufferBlocks, java.util.Optional.of(GeneticOptimizerConfig.defaults())); }
    }

    public static int runAllTests() {
        IntSupplier[] suites = { QO1Test::run, QO2Test::run, QO3Test::run, QO4Test::run, QO5Test::run };
        int failed = 0;
        int skipped = 0;
        for (int i = 0; i < suites.length; i++) {
            String name = "QO" + (i + 1);
            try {
                int result = suites[i].getAsInt();
                if (result < 0) {
                    skipped++;
                    System.out.println(name + ": SKIP (not implemented)");
                } else {
                    if (result > 0) failed++;
                    System.out.println(name + ": " + (result == 0 ? "PASS" : "FAIL (" + result + " tests)"));
                }
            } catch (RuntimeException | AssertionError e) {
                failed++;
                System.out.println(name + ": FAIL " + e);
            }
        }
        System.out.printf("Suites: %d passed, %d failed, %d skipped%n", suites.length - failed - skipped, failed, skipped);
        return failed;
    }
}
