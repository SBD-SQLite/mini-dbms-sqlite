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
        if (args.length >= 2 && args[0].equals("--explain")) {
            try { System.out.print(new QueryOptimizer(MockStorageManager.create()).explainQuery(args[1])); }
            catch (QoException e) { System.out.println(e.getInfo().code() + e.getInfo().position().map(p -> " at " + p).orElse("") + ": " + e.getMessage()); System.exit(1); }
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
