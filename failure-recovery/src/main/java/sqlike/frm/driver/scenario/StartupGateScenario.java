package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A1;
import static sqlike.frm.driver.Fixtures.account;
import static sqlike.frm.driver.Fixtures.values;

import java.util.Map;

import sqlike.frm.driver.Fixtures;
import sqlike.frm.driver.ScenarioContext;
import sqlike.frm.driver.TestScenario;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryResult;

/**
 * Startup order (FR-07, PRD §14): client logging is rejected before recovery, ensureDurable is not,
 * recover is repeatable, and only the startup owner (QP) calls CCM.initAfterRecovery.
 */
public class StartupGateScenario extends TestScenario {

    public StartupGateScenario() {
        super("startup-gate", "logging before recover() is rejected; QP-style startup calls initAfterRecovery once");
    }

    @Override
    public boolean startsRecovered() {
        return false;
    }

    @Override
    public void run(ScenarioContext ctx) {
        ctx.expectFailure("logBegin before recover() is rejected", () -> ctx.frm().logBegin(1));
        ctx.expectFailure("checkpoint before recover() is rejected", () -> ctx.frm().checkpoint());
        try {
            ctx.frm().ensureDurable(0);
        } catch (RuntimeException error) {
            ctx.check(false, "ensureDurable is allowed during startup: " + error);
        }

        RecoveryResult first = ctx.startup();
        ctx.check(ctx.ccm().getInitAfterRecoveryCalls() == 1,
                "initAfterRecovery called once at startup (by QP only), got " + ctx.ccm().getInitAfterRecoveryCalls());
        ctx.check(ctx.frm().recover().equals(first), "a second recover() returns the same result");

        ctx.begin(1);
        ctx.update(1, A1, values(account(1, "Ani", 1.5, "BDG")));
        ctx.commit(1);
        ctx.expectFailure("txId 0 is rejected", () -> ctx.frm().logBegin(0));
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A1, account(1, "Ani", 1.5, "BDG"))
                .build();
    }
}
