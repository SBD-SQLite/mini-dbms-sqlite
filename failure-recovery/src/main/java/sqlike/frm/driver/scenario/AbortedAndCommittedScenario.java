package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A1;
import static sqlike.frm.driver.Fixtures.A2;
import static sqlike.frm.driver.Fixtures.A3;
import static sqlike.frm.driver.Fixtures.A4;
import static sqlike.frm.driver.Fixtures.A5;
import static sqlike.frm.driver.Fixtures.account;
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
 * Committed, aborted, and active transactions together. T2's changes reach disk before it aborts,
 * and the undone pages are not flushed before the crash: a durable ABORT alone must not make
 * recovery skip the undo (PRD §14 step 5).
 */
public class AbortedAndCommittedScenario extends TestScenario {

    public AbortedAndCommittedScenario() {
        super("aborted-and-committed",
                "T1 commits, T2 (flushed) aborts with unflushed undo, T3 commits, T4 active -> T1+T3 only");
    }

    @Override
    public boolean crashes() {
        return true;
    }

    @Override
    public void run(ScenarioContext ctx) {
        ctx.begin(1);
        ctx.update(1, A1, values(account(1, "Ani", 200.0, "BDG")));
        ctx.commit(1);

        ctx.begin(2);
        ctx.update(2, A1, values(account(1, "Ani", 999.0, "XXX")));
        ctx.delete(2, A2);
        ctx.insert(2, A5, values(account(5, "Eka", 5.0, "MLG")));
        ctx.sm().flushAll(); // T2's changes reach disk
        ctx.abort(2);        // undo happens in the buffer only
        ctx.check(ctx.describe(A1).contains("200.0"), "after abort A1 is back to T1's value: " + ctx.describe(A1));
        ctx.check(ctx.describe(A2).contains("Budi"), "after abort A2 exists again: " + ctx.describe(A2));
        ctx.check(ctx.describe(A5).endsWith("absent"), "after abort A5 is gone: " + ctx.describe(A5));

        ctx.begin(3);
        ctx.update(3, A4, values(account(4, "Dewi", 900.0, "SBY")));
        ctx.commit(3);

        ctx.begin(4); // active, only in the buffer: no durable trace of T4 survives the crash
        ctx.update(4, A3, values(account(3, "Citra", 0.0, "BDG")));
        ctx.crash(CrashPoint.AFTER_ABORT_BEFORE_FLUSH);
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A1, account(1, "Ani", 200.0, "BDG"))
                .put(A4, account(4, "Dewi", 900.0, "SBY"))
                .build();
    }

    @Override
    public OptionalLong minimumLastTxId() {
        return OptionalLong.of(3); // T4 never reached the durable log
    }
}
