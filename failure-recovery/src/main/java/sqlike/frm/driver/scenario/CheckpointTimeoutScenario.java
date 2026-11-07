package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A1;
import static sqlike.frm.driver.Fixtures.account;
import static sqlike.frm.driver.Fixtures.values;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import sqlike.frm.driver.Fixtures;
import sqlike.frm.driver.ScenarioContext;
import sqlike.frm.driver.TestScenario;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * Quiescent checkpoint (FR-06, PRD §15): an active transaction makes the checkpoint time out
 * before any flush, the gate is always reopened, and once idle the checkpoint flushes every page.
 */
public class CheckpointTimeoutScenario extends TestScenario {

    public CheckpointTimeoutScenario() {
        super("checkpoint-timeout",
                "checkpoint times out while T1 is active (no flush, gate reopened), succeeds after T1 commits");
    }

    @Override
    public Duration idleTimeout() {
        return Duration.ofMillis(300);
    }

    @Override
    public void run(ScenarioContext ctx) {
        ctx.begin(1);
        ctx.update(1, A1, values(account(1, "Ani", 7.0, "BDG")));

        ctx.expectFailure("checkpoint with an active transaction times out", () -> ctx.frm().checkpoint());
        ctx.check(!ctx.ccm().isPaused(), "new transactions are accepted again after the timeout");
        ctx.check(ctx.sm().hasDirtyTables(), "a cancelled checkpoint did not flush data");
        List<String> calls = ctx.ccm().getCalls();
        ctx.check(!calls.isEmpty() && calls.get(calls.size() - 1).equals("resume"),
                "the last CCM call after a timeout is resume: " + calls);

        ctx.commit(1);
        ctx.frm().checkpoint();
        ctx.check(!ctx.sm().hasDirtyTables(), "a completed checkpoint flushed every dirty page");
        ctx.check(!ctx.ccm().isPaused(), "new transactions are accepted again after the checkpoint");
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A1, account(1, "Ani", 7.0, "BDG"))
                .build();
    }
}
