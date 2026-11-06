package sqlike.frm.driver;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.port.SmPort;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * One recovery test case (FR-08): prepare a known state, run interleaved transactions, then
 * compare the stored data with the expected data.
 */
public abstract class TestScenario {

    private final String name;

    protected TestScenario(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** Puts the data into the known initial state of this scenario. */
    public abstract void setup(IFailureRecoveryManager frm, SmPort sm);

    /** Runs the scenario's transactions, including any crash and recovery. */
    public abstract void run(IFailureRecoveryManager frm, SmPort sm);

    /** Expected record per RID after the scenario; {@code exists == false} means no record. */
    public abstract Map<RID, RecordImage> expected();

    /** Compares every expected record with what SM actually holds. */
    public ScenarioResult verify(SmPort sm) {
        Map<RID, RecordImage> expected = expected();
        Map<RID, RecordImage> actual = new HashMap<>();
        for (RID rid : expected.keySet()) {
            actual.put(rid, sm.readAt(rid.pageId().tableId(), rid));
        }
        boolean passed = expected.equals(actual);
        Optional<String> message = passed ? Optional.empty() : Optional.of("data differs from expected");
        return new ScenarioResult(name, passed, expected.toString(), actual.toString(), message);
    }
}
