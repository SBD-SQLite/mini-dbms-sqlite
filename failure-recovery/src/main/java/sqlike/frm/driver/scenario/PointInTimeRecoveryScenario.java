package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A1;
import static sqlike.frm.driver.Fixtures.A2;
import static sqlike.frm.driver.Fixtures.A5;
import static sqlike.frm.driver.Fixtures.account;
import static sqlike.frm.driver.Fixtures.values;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import sqlike.frm.driver.CrashPoint;
import sqlike.frm.driver.Fixtures;
import sqlike.frm.driver.ScenarioContext;
import sqlike.frm.driver.TestScenario;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;

/**
 * PITR to a committed transaction (FR-05). T2 writes before the target but commits after it, so it
 * must not be replayed. The result must survive a restart, and the restart must not bring back
 * the old timeline's later commits (PRD §15).
 */
public class PointInTimeRecoveryScenario extends TestScenario {

    public PointInTimeRecoveryScenario() {
        super("point-in-time-recovery",
                "T2 writes before T1's COMMIT but commits after; recoverTo(T1); crash -> only T1 kept");
    }

    @Override
    public boolean crashes() {
        return true;
    }

    @Override
    public void run(ScenarioContext ctx) {
        ctx.begin(1);
        ctx.update(1, A1, values(account(1, "Ani", 500.0, "BDG")));
        ctx.begin(2);
        ctx.update(2, A2, values(account(2, "Budi", 0.5, "JKT")));
        ctx.commit(1); // target boundary
        ctx.commit(2);

        ctx.begin(3);
        ctx.insert(3, A5, values(account(5, "Eka", 42.0, "MLG")));
        ctx.commit(3);

        ctx.expectFailure("recoverTo rejects a TRANSACTION target without txId", () -> ctx.frm().recoverTo(
                new RecoveryTarget(RecoveryTargetKind.TRANSACTION, Optional.empty(), Optional.empty())));
        ctx.frm().recoverTo(new RecoveryTarget(RecoveryTargetKind.TRANSACTION, Optional.empty(), Optional.of(1L)));

        ctx.check(ctx.describe(A1).contains("500.0"), "A1 keeps T1's value: " + ctx.describe(A1));
        ctx.check(ctx.describe(A2).contains("250.5"), "A2 is back to the baseline: " + ctx.describe(A2));
        ctx.check(ctx.describe(A5).endsWith("absent"), "T3's INSERT is gone: " + ctx.describe(A5));
        ctx.check(ctx.ccm().getMaxTxIdAfterRecovery() >= 3,
                "CCM continues above the old high-water mark, got " + ctx.ccm().getMaxTxIdAfterRecovery());
        ctx.check(!ctx.ccm().isPaused(), "new transactions are accepted again after PITR");
        ctx.crash(CrashPoint.AFTER_POINT_IN_TIME_RECOVERY);
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A1, account(1, "Ani", 500.0, "BDG"))
                .build();
    }

    @Override
    public OptionalLong minimumLastTxId() {
        return OptionalLong.of(3);
    }
}
