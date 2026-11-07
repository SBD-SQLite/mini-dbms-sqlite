package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A2;
import static sqlike.frm.driver.Fixtures.A4;
import static sqlike.frm.driver.Fixtures.A5;
import static sqlike.frm.driver.Fixtures.account;
import static sqlike.frm.driver.Fixtures.values;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import sqlike.frm.driver.Fixtures;
import sqlike.frm.driver.ScenarioContext;
import sqlike.frm.driver.TestScenario;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * Log content and display (FR-01..FR-03): unique increasing LSNs, BEGIN/change/COMMIT markers per
 * transaction, change records with table, RID and before/after image, filtering by txId, and
 * reading that does not change the log.
 */
public class LogViewScenario extends TestScenario {

    public LogViewScenario() {
        super("log-view", "T1 INSERT+COMMIT, T2 UPDATE+COMMIT, T3 DELETE active -> log order, content, txId filter");
    }

    @Override
    public void run(ScenarioContext ctx) {
        long b1 = ctx.begin(1);
        long c1 = ctx.insert(1, A5, values(account(5, "Eka", 42.0, "MLG")));
        ctx.commit(1);
        long b2 = ctx.begin(2);
        long c2 = ctx.update(2, A2, values(account(2, "Budi", 9.0, "JKT")));
        ctx.commit(2);
        ctx.begin(3);
        ctx.delete(3, A4);

        List<LogRecord> all = ctx.readLogs(Optional.empty());
        ctx.check(all.size() >= 8, "all 8 records are readable, found " + all.size());
        for (int i = 1; i < all.size(); i++) {
            ctx.check(all.get(i).lsn() > all.get(i - 1).lsn(), "LSNs strictly increase at index " + i);
        }
        ctx.check(b1 > 0 && c1 > b1 && b2 > c1 && c2 > b2, "returned LSNs increase: " + List.of(b1, c1, b2, c2));

        checkTypes(ctx, 1, List.of(LogType.BEGIN, LogType.INSERT, LogType.COMMIT));
        checkTypes(ctx, 2, List.of(LogType.BEGIN, LogType.UPDATE, LogType.COMMIT));
        checkTypes(ctx, 3, List.of(LogType.BEGIN, LogType.DELETE));

        Optional<LogRecord> insert = find(all, c1);
        ctx.check(insert.isPresent(), "INSERT record is found by the LSN logChange returned");
        insert.flatMap(LogRecord::change).ifPresent(change -> checkChange(ctx, change,
                new DataChange(ChangeOperation.INSERT, Fixtures.ACCOUNT, A5, new RecordImage(false, List.of()),
                        new RecordImage(true, account(5, "Eka", 42.0, "MLG")))));
        Optional<LogRecord> update = find(all, c2);
        update.flatMap(LogRecord::change).ifPresent(change -> checkChange(ctx, change,
                new DataChange(ChangeOperation.UPDATE, Fixtures.ACCOUNT, A2,
                        new RecordImage(true, account(2, "Budi", 250.5, "JKT")),
                        new RecordImage(true, account(2, "Budi", 9.0, "JKT")))));

        ctx.check(ctx.readLogs(Optional.empty()).equals(all), "reading the log twice gives the same records");
        ctx.check(!ctx.showLogs(Optional.of(1L)).isBlank(), "showLogs(T1) prints something");
        ctx.check(ctx.readLogs(Optional.of(42L)).isEmpty(), "filter on an unknown txId is empty");
    }

    private static void checkTypes(ScenarioContext ctx, long txId, List<LogType> expected) {
        List<LogType> actual = new ArrayList<>();
        for (LogRecord record : ctx.readLogs(Optional.of(txId))) {
            ctx.check(record.txID().equals(Optional.of(txId)), "filter T" + txId + " returned " + record);
            actual.add(record.type());
        }
        ctx.check(actual.equals(expected), "T" + txId + " record types " + actual + ", expected " + expected);
    }

    private static Optional<LogRecord> find(List<LogRecord> records, long lsn) {
        return records.stream().filter(record -> record.lsn() == lsn).findFirst();
    }

    private static void checkChange(ScenarioContext ctx, DataChange actual, DataChange expected) {
        ctx.check(actual.equals(expected), "logged change " + actual + " equals " + expected);
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        return Fixtures.expectedFromBaseline()
                .put(A5, account(5, "Eka", 42.0, "MLG"))
                .put(A2, account(2, "Budi", 9.0, "JKT"))
                .remove(A4)
                .build();
    }
}
