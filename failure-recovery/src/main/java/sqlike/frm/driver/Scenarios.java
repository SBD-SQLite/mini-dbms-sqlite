package sqlike.frm.driver;

import java.util.List;
import java.util.Optional;

import sqlike.frm.driver.scenario.AbortedAndCommittedScenario;
import sqlike.frm.driver.scenario.CheckpointRecoveryScenario;
import sqlike.frm.driver.scenario.CheckpointTimeoutScenario;
import sqlike.frm.driver.scenario.CommittedNotFlushedScenario;
import sqlike.frm.driver.scenario.LogViewScenario;
import sqlike.frm.driver.scenario.PointInTimeRecoveryScenario;
import sqlike.frm.driver.scenario.RollbackScenario;
import sqlike.frm.driver.scenario.SchemaChangeLogScenario;
import sqlike.frm.driver.scenario.StartupGateScenario;
import sqlike.frm.driver.scenario.UncommittedFlushedScenario;

/** Every scenario the driver knows, in run order. Add new scenarios here. */
public final class Scenarios {

    private Scenarios() {
    }

    public static List<TestScenario> all() {
        return List.of(
                new StartupGateScenario(),
                new LogViewScenario(),
                new SchemaChangeLogScenario(),
                new RollbackScenario(),
                new CheckpointTimeoutScenario(),
                new CommittedNotFlushedScenario(),
                new UncommittedFlushedScenario(),
                new AbortedAndCommittedScenario(),
                new CheckpointRecoveryScenario(),
                new PointInTimeRecoveryScenario());
    }

    public static Optional<TestScenario> byName(String name) {
        return all().stream().filter(scenario -> scenario.name().equals(name)).findFirst();
    }
}
