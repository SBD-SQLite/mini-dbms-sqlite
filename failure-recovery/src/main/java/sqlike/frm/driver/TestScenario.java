package sqlike.frm.driver;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;

import sqlike.frm.mock.MockStorageManager;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * One recovery test case (FR-08): prepare a known state, run transactions, then compare the data
 * with the expected data.
 *
 * <p>Two kinds:
 * <ul>
 *   <li><b>Crash scenarios</b> ({@link #crashes()} true) run {@link #run} in a child JVM that ends
 *       with {@link ScenarioContext#crash(CrashPoint)} and is killed without a graceful shutdown.
 *       The driver then runs startup recovery twice, each in a new process, and compares the data
 *       on disk after each one with {@link #expected()}.</li>
 *   <li><b>In-process scenarios</b> run {@link #run} in the driver and compare the current data.</li>
 * </ul>
 */
public abstract class TestScenario {

    private final String name;
    private final String description;

    protected TestScenario(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** True when the scenario ends with a process kill and is verified after recovery. */
    public boolean crashes() {
        return false;
    }

    /** False when {@link #run} must observe FRM before startup recovery (in-process only). */
    public boolean startsRecovered() {
        return true;
    }

    /** Idle timeout given to checkpoint and PITR in this scenario. */
    public Duration idleTimeout() {
        return Duration.ofSeconds(2);
    }

    /** Puts the known initial data on disk; runs before FRM exists, so nothing is logged. */
    public void setup(MockStorageManager sm) {
        Fixtures.loadBaseline(sm);
    }

    /** Runs the scenario's transactions; crash scenarios end with {@code ctx.crash(...)}. */
    public abstract void run(ScenarioContext ctx);

    /** Full expected content of every table after the scenario (and after recovery). */
    public abstract Map<String, Map<RID, RecordImage>> expected();

    /** Lower bound for {@code RecoveryResult.lastTxId} after recovery, when it matters. */
    public OptionalLong minimumLastTxId() {
        return OptionalLong.empty();
    }
}
