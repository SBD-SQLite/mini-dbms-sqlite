package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A1;
import static sqlike.frm.driver.Fixtures.A2;
import static sqlike.frm.driver.Fixtures.A3;
import static sqlike.frm.driver.Fixtures.A5;
import static sqlike.frm.driver.Fixtures.account;
import static sqlike.frm.driver.Fixtures.values;

import java.util.Map;
import java.util.Optional;

import sqlike.frm.driver.Fixtures;
import sqlike.frm.driver.ScenarioContext;
import sqlike.frm.driver.TestScenario;
import sqlike.frm.log.LogType;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * Normal rollback (FR-04, PRD §11): undo in reverse order on the same RIDs, CCM writes one final
 * ABORT, a repeated rollback after another transaction changed the record leaves data alone, and
 * committed or unknown transactions are rejected.
 */
public class RollbackScenario extends TestScenario {

    public RollbackScenario() {
        super("rollback",
                "T1 (UPDATE x2, DELETE, INSERT) aborts while T2 commits; repeat rollback/logAbort is a no-op");
    }

    @Override
    public void run(ScenarioContext ctx) {
        ctx.begin(1);
        ctx.update(1, A1, values(account(1, "Ani", 1.0, "BDG")));
        ctx.delete(1, A2);
        ctx.insert(1, A5, values(account(5, "Eka", 42.0, "MLG")));
        ctx.update(1, A1, values(account(1, "Ani", 2.0, "JKT")));

        ctx.begin(2);
        ctx.update(2, A3, values(account(3, "Citra", 33.0, "BDG")));
        ctx.commit(2);

        ctx.abort(1);
        ctx.check(ctx.describe(A1).contains("100.0"), "A1 restored to its first before-image: " + ctx.describe(A1));
        ctx.check(ctx.describe(A2).contains("Budi"), "deleted A2 restored on the same RID: " + ctx.describe(A2));
        ctx.check(ctx.describe(A5).endsWith("absent"), "inserted A5 removed: " + ctx.describe(A5));
        ctx.check(ctx.describe(A3).contains("33.0"), "committed T2 untouched: " + ctx.describe(A3));

        ctx.begin(3);
        ctx.update(3, A1, values(account(1, "Ani", 300.0, "BDG")));
        ctx.commit(3);

        ctx.frm().rollback(1);  // already aborted: must not change data
        ctx.frm().logAbort(1);  // idempotent: no second ABORT
        ctx.check(ctx.describe(A1).contains("300.0"),
                "repeated rollback of T1 keeps T3's later value: " + ctx.describe(A1));
        long aborts = ctx.readLogs(Optional.of(1L)).stream().filter(r -> r.type() == LogType.ABORT).count();
        ctx.check(aborts == 1, "exactly one ABORT record for T1, found " + aborts);

        ctx.expectFailure("rollback of committed T2 is rejected", () -> ctx.frm().rollback(2));
        ctx.expectFailure("rollback of unknown T99 is rejected", () -> ctx.frm().rollback(99));
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A3, account(3, "Citra", 33.0, "BDG"))
                .put(A1, account(1, "Ani", 300.0, "BDG"))
                .build();
    }
}
