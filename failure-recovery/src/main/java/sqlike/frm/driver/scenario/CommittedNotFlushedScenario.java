package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A1;
import static sqlike.frm.driver.Fixtures.A2;
import static sqlike.frm.driver.Fixtures.A3;
import static sqlike.frm.driver.Fixtures.A5;
import static sqlike.frm.driver.Fixtures.L1;
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
 * Redo: three committed transactions whose pages never reached disk must survive a crash.
 * Covers INSERT, UPDATE, DELETE, two tables, and two updates of the same record.
 */
public class CommittedNotFlushedScenario extends TestScenario {

    public CommittedNotFlushedScenario() {
        super("committed-not-flushed",
                "T1..T3 commit (INSERT/UPDATE/DELETE, A3 updated twice), no page is flushed, crash -> all redone");
    }

    @Override
    public boolean crashes() {
        return true;
    }

    @Override
    public void run(ScenarioContext ctx) {
        ctx.begin(1);
        ctx.update(1, A1, values(account(1, "Ani", 150.0, "BDG")));
        ctx.insert(1, A5, values(account(5, "Eka", 42.0, "MLG")));
        ctx.commit(1);

        ctx.begin(2);
        ctx.delete(2, A2);
        ctx.update(2, L1, values(loan(1, 1, 450.0)));
        ctx.commit(2);

        ctx.begin(3);
        ctx.update(3, A3, values(account(3, "Citra", 80.0, "BDG")));
        ctx.update(3, A3, values(account(3, "Citra", 95.5, "JKT")));
        ctx.commit(3);

        ctx.check(ctx.sm().hasDirtyTables(), "committed pages are still only in the buffer before the crash");
        ctx.crash(CrashPoint.AFTER_COMMIT_BEFORE_FLUSH);
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A1, account(1, "Ani", 150.0, "BDG"))
                .put(A5, account(5, "Eka", 42.0, "MLG"))
                .remove(A2)
                .put(L1, loan(1, 1, 450.0))
                .put(A3, account(3, "Citra", 95.5, "JKT"))
                .build();
    }

    @Override
    public OptionalLong minimumLastTxId() {
        return OptionalLong.of(3);
    }
}
