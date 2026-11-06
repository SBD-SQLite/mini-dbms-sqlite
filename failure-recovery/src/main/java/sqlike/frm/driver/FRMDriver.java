package sqlike.frm.driver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.FailureRecoveryManager;
import sqlike.frm.driver.scenario.AbortedAndCommittedScenario;
import sqlike.frm.driver.scenario.CheckpointRecoveryScenario;
import sqlike.frm.driver.scenario.CommittedNotFlushedScenario;
import sqlike.frm.driver.scenario.PointInTimeRecoveryScenario;
import sqlike.frm.driver.scenario.UncommittedFlushedScenario;
import sqlike.frm.mock.MockCCM;
import sqlike.frm.mock.MockStorageManager;

/**
 * Standalone FRM test driver (FR-09): runs recovery scenarios against mock CCM and SM without
 * the QP CLI, and prints expected data, actual data, and PASS/FAIL per scenario.
 *
 * <p>Usage: {@code FRMDriver [scenario-name]}; without a name every scenario runs.
 */
public class FRMDriver {

    private static final Path DATA_DIR = Path.of("frm-data");
    private static final String LOG_PATH = DATA_DIR.resolve("wal.log").toString();

    private IFailureRecoveryManager frm;
    private MockStorageManager sm;
    private MockCCM ccm;
    private final List<TestScenario> scenarios = List.of(
            new CommittedNotFlushedScenario(),
            new UncommittedFlushedScenario(),
            new AbortedAndCommittedScenario(),
            new CheckpointRecoveryScenario(),
            new PointInTimeRecoveryScenario());
    private final CrashSimulator crashSim = new CrashSimulator(DATA_DIR);

    public static void main(String[] args) {
        FRMDriver driver = new FRMDriver();
        List<ScenarioResult> results = args.length == 0
                ? driver.runAll()
                : List.of(driver.runScenario(args[0]));
        driver.printReport(results);
    }

    public ScenarioResult runScenario(String name) {
        for (TestScenario scenario : scenarios) {
            if (scenario.name().equals(name)) {
                return run(scenario);
            }
        }
        return new ScenarioResult(name, false, "-", "-", Optional.of("unknown scenario"));
    }

    public List<ScenarioResult> runAll() {
        List<ScenarioResult> results = new ArrayList<>();
        for (TestScenario scenario : scenarios) {
            results.add(run(scenario));
        }
        return results;
    }

    /** Starts every scenario from the same known state: fresh mocks and a recovered FRM. */
    public void resetState() {
        // TODO(FR1): delete the files in DATA_DIR once MockStorageManager and the log persist there.
        sm = new MockStorageManager(DATA_DIR);
        ccm = new MockCCM();
        frm = new FailureRecoveryManager(sm, ccm, LOG_PATH);
        frm.recover();
    }

    public void printReport(List<ScenarioResult> results) {
        int passed = 0;
        for (ScenarioResult result : results) {
            System.out.printf("[%s] %s%n", result.passed() ? "PASS" : "FAIL", result.name());
            System.out.println("  expected: " + result.expected());
            System.out.println("  actual  : " + result.actual());
            result.message().ifPresent(message -> System.out.println("  message : " + message));
            if (result.passed()) {
                passed++;
            }
        }
        System.out.printf("%d/%d scenarios passed%n", passed, results.size());
    }

    private ScenarioResult run(TestScenario scenario) {
        resetState();
        try {
            scenario.setup(frm, sm);
            scenario.run(frm, sm);
            return scenario.verify(sm);
        } catch (RuntimeException e) {
            return new ScenarioResult(scenario.name(), false, "-", "-", Optional.of(e.getMessage()));
        }
    }
}
