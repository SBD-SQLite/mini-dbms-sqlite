package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A1;
import static sqlike.frm.driver.Fixtures.A2;
import static sqlike.frm.driver.Fixtures.A4;
import static sqlike.frm.driver.Fixtures.L2;
import static sqlike.frm.driver.Fixtures.account;
import static sqlike.frm.driver.Fixtures.loan;
import static sqlike.frm.driver.Fixtures.values;

import java.util.Map;
import java.util.OptionalLong;

import sqlike.frm.driver.CrashPoint;
import sqlike.frm.driver.Fixtures;
import sqlike.frm.driver.ScenarioContext;
import sqlike.frm.driver.TestScenario;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * Recovery with a completed checkpoint: work before the checkpoint is on disk, a committed
 * transaction after it is redone, and an unfinished one after it is undone.
 */
public class CheckpointRecoveryScenario extends TestScenario {

    public CheckpointRecoveryScenario() {
        super("checkpoint-recovery",
                "T1 commits, checkpoint, T2 commits (unflushed), T3 active (evicted) -> T1+T2 only");
    }

    @Override
    public boolean crashes() {
        return true;
    }

    @Override
    public void run(ScenarioContext ctx) {
        ctx.begin(1);
        ctx.update(1, A1, values(account(1, "Ani", 111.0, "BDG")));
        ctx.update(1, L2, values(loan(2, 3, 60.0)));
        ctx.commit(1);

        ctx.frm().checkpoint();
        ctx.check(!ctx.sm().hasDirtyTables(), "checkpoint flushed every dirty page");
        ctx.check(!ctx.ccm().isPaused(), "checkpoint resumed new transactions");

        ctx.begin(2);
        ctx.update(2, A2, values(account(2, "Budi", 222.0, "JKT")));
        ctx.commit(2);

        ctx.begin(3);
        ctx.delete(3, A4);
        ctx.sm().evict(Fixtures.ACCOUNT); // T3's DELETE (and T2's update) reach disk
        ctx.crash(CrashPoint.AFTER_CHECKPOINT);
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A1, account(1, "Ani", 111.0, "BDG"))
                .put(L2, loan(2, 3, 60.0))
                .put(A2, account(2, "Budi", 222.0, "JKT"))
                .build();
    }

    @Override
    public OptionalLong minimumLastTxId() {
        return OptionalLong.of(3);
    }
}
