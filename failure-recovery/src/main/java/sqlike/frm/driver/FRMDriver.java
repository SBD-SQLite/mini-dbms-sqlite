package sqlike.frm.driver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import sqlike.frm.log.LogRecord;
import sqlike.frm.mock.MockStorageManager;
import sqlike.frm.mock.RecordCodec;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryResult;

/**
 * Standalone FRM test driver (FR-09). Runs recovery scenarios against the file-based mock SM and
 * mock CCM without the QP CLI, and prints expected data, actual data, and PASS/FAIL per scenario.
 *
 * <pre>
 * FRMDriver [--data-dir DIR] [scenario ...]        run all or the named scenarios
 * FRMDriver --list                                  list scenarios
 * FRMDriver [--data-dir DIR] --show-log SCENARIO [TXID]
 *                                                   print the WAL a scenario left behind
 * </pre>
 *
 * Each scenario starts from a wiped {@code DIR/<scenario>} directory with the baseline fixture,
 * so runs repeat without code changes. The directory is kept afterwards for inspection.
 * Exit status is 0 only when every scenario passed.
 */
public class FRMDriver {

    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(60);

    private final Path dataRoot;
    private final FrmFactory factory;
    private final CrashSimulator crashSim = new CrashSimulator(CHILD_TIMEOUT);

    public FRMDriver(Path dataRoot, FrmFactory factory) {
        this.dataRoot = dataRoot;
        this.factory = factory;
    }

    public static void main(String[] args) {
        Path dataRoot = Path.of("frm-data");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> dataRoot = Path.of(args[++i]);
                case "--list" -> {
                    for (TestScenario scenario : Scenarios.all()) {
                        System.out.printf("%-26s %s %s%n", scenario.name(),
                                scenario.crashes() ? "[crash]" : "[in-process]", scenario.description());
                    }
                    return;
                }
                case "--show-log" -> {
                    Optional<Long> txId = i + 2 < args.length ? Optional.of(Long.parseLong(args[i + 2])) : Optional.empty();
                    new FRMDriver(dataRoot, FrmFactory.selected()).showLog(args[i + 1], txId);
                    return;
                }
                default -> names.add(args[i]);
            }
        }
        FRMDriver driver = new FRMDriver(dataRoot, FrmFactory.selected());
        List<ScenarioResult> results = names.isEmpty() ? driver.runAll() : driver.runNamed(names);
        boolean allPassed = driver.printReport(results);
        System.exit(allPassed ? 0 : 1);
    }

    public List<ScenarioResult> runAll() {
        List<ScenarioResult> results = new ArrayList<>();
        for (TestScenario scenario : Scenarios.all()) {
            results.add(run(scenario));
        }
        return results;
    }

    public List<ScenarioResult> runNamed(List<String> names) {
        List<ScenarioResult> results = new ArrayList<>();
        for (String name : names) {
            results.add(Scenarios.byName(name).map(this::run).orElseGet(
                    () -> new ScenarioResult(name, false, "-", "-", Optional.of("unknown scenario; see --list"))));
        }
        return results;
    }

    /** Wipes the scenario directory and writes the baseline fixture (known initial state). */
    public Path resetState(TestScenario scenario) {
        Path dir = dataRoot.resolve(scenario.name());
        MockStorageManager.wipe(dir);
        scenario.setup(new MockStorageManager(dir));
        return dir;
    }

    public ScenarioResult run(TestScenario scenario) {
        System.out.println("== " + scenario.name() + ": " + scenario.description());
        try {
            Path dir = resetState(scenario);
            return scenario.crashes() ? runWithCrash(scenario, dir) : runInProcess(scenario, dir);
        } catch (RuntimeException error) {
            return new ScenarioResult(scenario.name(), false, format(scenario.expected()), "-",
                    Optional.of("error: " + describe(error)));
        }
    }

    private ScenarioResult runInProcess(TestScenario scenario, Path dir) {
        Map<String, Map<RID, RecordImage>> expected = scenario.expected();
        try (ScenarioContext ctx = ScenarioContext.open(dir, factory, scenario.idleTimeout())) {
            try {
                if (scenario.startsRecovered()) {
                    ctx.startup();
                }
                scenario.run(ctx);
            } catch (RuntimeException error) {
                return new ScenarioResult(scenario.name(), false, format(expected),
                        format(ctx.sm().dumpAll()), Optional.of("error: " + describe(error)));
            }
            Map<String, Map<RID, RecordImage>> actual = ctx.sm().dumpAll();
            List<String> problems = new ArrayList<>(ctx.failedChecks());
            if (!actual.equals(expected)) {
                problems.add(0, "data differs from expected");
            }
            return result(scenario, expected, actual, problems);
        }
    }

    private ScenarioResult runWithCrash(TestScenario scenario, Path dir) {
        Map<String, Map<RID, RecordImage>> expected = scenario.expected();
        CrashSimulator.CrashRun crash = crashSim.runUntilCrash(scenario, dir, scenario.idleTimeout());
        if (!crash.reachedCrashPoint()) {
            return new ScenarioResult(scenario.name(), false, format(expected), "-",
                    Optional.of("workload process ended before its crash point:\n" + tail(crash.output())));
        }
        System.out.println("   killed child at " + crash.crashPoint().orElse("?"));
        List<String> problems = new ArrayList<>(crash.failedChecks());

        CrashSimulator.RecoveryRun first = crashSim.restartAndRecover(dir, scenario.idleTimeout());
        if (!first.succeeded()) {
            problems.add(0, "recovery in a new process failed:\n" + tail(first.output()));
            return result(scenario, expected, new MockStorageManager(dir).dumpAll(), problems);
        }
        Map<String, Map<RID, RecordImage>> afterFirst = new MockStorageManager(dir).dumpAll();
        System.out.println("   recovered: " + first.result().map(FRMDriver::format).orElse("?"));

        CrashSimulator.RecoveryRun second = crashSim.restartAndRecover(dir, scenario.idleTimeout());
        Map<String, Map<RID, RecordImage>> afterSecond = new MockStorageManager(dir).dumpAll();
        if (!second.succeeded()) {
            problems.add("second restart failed:\n" + tail(second.output()));
        } else if (!afterSecond.equals(afterFirst)) {
            problems.add("second restart changed the data (recovery is not repeatable): " + format(afterSecond));
        }
        if (!afterFirst.equals(expected)) {
            problems.add(0, "data on disk after recovery differs from expected");
        }
        OptionalLong minTxId = scenario.minimumLastTxId();
        long lastTxId = first.result().map(RecoveryResult::lastTxId).orElse(-1L);
        if (minTxId.isPresent() && lastTxId < minTxId.getAsLong()) {
            problems.add("RecoveryResult.lastTxId is " + lastTxId + ", expected at least " + minTxId.getAsLong());
        }
        return result(scenario, expected, afterFirst, problems);
    }

    /** Prints the WAL that {@code scenarioName} left in its directory (FR-03), without changing it. */
    public void showLog(String scenarioName, Optional<Long> txId) {
        Path dir = dataRoot.resolve(scenarioName);
        try (ScenarioContext ctx = ScenarioContext.open(dir, factory, Duration.ofSeconds(1))) {
            List<LogRecord> records = ctx.readLogs(txId);
            System.out.printf("WAL of %s%s: %d record(s)%n", dir,
                    txId.map(id -> " filtered on tx " + id).orElse(""), records.size());
            for (LogRecord record : records) {
                System.out.println("  " + format(record));
            }
            String text = ctx.showLogs(txId);
            if (!text.isBlank()) {
                System.out.println("-- FRM showLogs --");
                System.out.println(text);
            }
        }
    }

    /** Prints every result and a summary; returns true when all passed. */
    public boolean printReport(List<ScenarioResult> results) {
        int passed = 0;
        System.out.println();
        for (ScenarioResult result : results) {
            System.out.printf("[%s] %s%n", result.passed() ? "PASS" : "FAIL", result.name());
            System.out.println("  expected: " + result.expected().replace("\n", "\n            "));
            System.out.println("  actual  : " + result.actual().replace("\n", "\n            "));
            result.message().ifPresent(message ->
                    System.out.println("  message : " + message.replace("\n", "\n            ")));
            if (result.passed()) {
                passed++;
            }
        }
        System.out.printf("%n%d/%d scenarios passed%n", passed, results.size());
        return passed == results.size();
    }

    // ------------------------------------------------------------------ formatting

    private static ScenarioResult result(TestScenario scenario, Map<String, Map<RID, RecordImage>> expected,
            Map<String, Map<RID, RecordImage>> actual, List<String> problems) {
        boolean passed = problems.isEmpty() && actual.equals(expected);
        Optional<String> message = problems.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", problems));
        return new ScenarioResult(scenario.name(), passed, format(expected), format(actual), message);
    }

    static String format(Map<String, Map<RID, RecordImage>> tables) {
        if (tables.isEmpty()) {
            return "(no records)";
        }
        List<String> lines = new ArrayList<>();
        tables.forEach((table, rows) -> {
            List<String> cells = new ArrayList<>();
            rows.forEach((rid, image) -> cells.add("(" + rid.pageId().pageNumber() + "," + rid.slotId() + ")="
                    + RecordCodec.show(image.values())));
            lines.add(table + ": " + String.join(" ", cells));
        });
        return String.join("\n", lines);
    }

    private static String format(RecoveryResult result) {
        return "lastTxId=" + result.lastTxId() + " lastTimestamp=" + result.lastTimestamp()
                + " lastRecoveredLSN=" + result.lastRecoveredLSN()
                + " checkpointLSN=" + result.checkpointLSN().map(String::valueOf).orElse("-");
    }

    private static String format(LogRecord record) {
        StringBuilder line = new StringBuilder();
        line.append(String.format("LSN %-4d %-10s tx=%-4s %s", record.lsn(), record.type(),
                record.txID().map(String::valueOf).orElse("-"), record.time()));
        record.change().ifPresent(change -> line.append("  ").append(RecordCodec.show(change.rid()))
                .append(" before=").append(change.before().exists() ? RecordCodec.show(change.before().values()) : "absent")
                .append(" after=").append(change.after().exists() ? RecordCodec.show(change.after().values()) : "absent"));
        record.checkpoint().ifPresent(info -> line.append("  flushedThrough=").append(info.flushedThroughLSN())
                .append(" snapshot=").append(info.snapshotId()));
        return line.toString();
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(error.toString());
        Throwable cause = error.getCause();
        while (cause != null && cause != error) {
            text.append("\n  caused by ").append(cause);
            error = cause;
            cause = cause.getCause();
        }
        return text.toString();
    }

    private static String tail(List<String> output) {
        List<String> useful = output.stream()
                .filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS")) // JVM noise
                .toList();
        int from = Math.max(0, useful.size() - 12);
        return useful.isEmpty() ? "(no output)" : String.join("\n", useful.subList(from, useful.size()));
    }
}
