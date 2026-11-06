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
import sqlike.frm.mock.MockStorageManager;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * Undo: an unfinished transaction's pages reach disk (STEAL, WAL respected) and must be undone,
 * while a committed transaction interleaved with it is kept.
 */
public class UncommittedFlushedScenario extends TestScenario {

    public UncommittedFlushedScenario() {
        super("uncommitted-flushed",
                "T1 active (A1 updated twice, INSERT A5, DELETE A3) and T3 active are flushed, T2 commits -> only T2 kept");
    }

    @Override
    public boolean crashes() {
        return true;
    }

    @Override
    public void run(ScenarioContext ctx) {
        ctx.begin(1);
        ctx.update(1, A1, values(account(1, "Ani", 10.0, "BDG")));
        ctx.begin(2);
        ctx.update(2, A2, values(account(2, "Budi", 300.0, "JKT")));
        ctx.update(1, A1, values(account(1, "Ani", 20.0, "SBY")));
        ctx.insert(1, A5, values(account(5, "Eka", 42.0, "MLG")));
        ctx.delete(1, A3);
        ctx.commit(2);

        ctx.sm().flushAll(); // STEAL: T1's uncommitted changes are now on disk
        MockStorageManager disk = new MockStorageManager(ctx.dataDir());
        ctx.check(disk.readAt(Fixtures.ACCOUNT, A5).exists(),
                "T1's uncommitted INSERT really reached disk before the crash");

        ctx.begin(3);
        ctx.update(3, L1, values(loan(1, 1, 1.0)));
        ctx.sm().evict(Fixtures.LOAN); // T3's page reaches disk too, so its log is durable
        ctx.crash(CrashPoint.AFTER_FLUSH_BEFORE_COMMIT);
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A2, account(2, "Budi", 300.0, "JKT"))
                .build();
    }

    @Override
    public OptionalLong minimumLastTxId() {
        return OptionalLong.of(3);
    }
}
