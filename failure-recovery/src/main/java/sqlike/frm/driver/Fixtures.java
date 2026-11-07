package sqlike.frm.driver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import sqlike.frm.mock.MockStorageManager;
import sqlike.frm.mock.RecordCodec;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.Value;

/**
 * Known initial state shared by every scenario: two related tables using all four value types.
 *
 * <pre>
 * account(id INTEGER, owner VARCHAR, balance FLOAT, branch CHAR)   page 0, slots 0..3
 * loan(id INTEGER, account_id INTEGER, amount FLOAT)                page 0, slots 0..1
 * </pre>
 *
 * Slot 4 of {@code account} is free and is used by INSERT scenarios.
 */
public final class Fixtures {

    public static final String ACCOUNT = "account";
    public static final String LOAN = "loan";

    public static final RID A1 = rid(ACCOUNT, 0);
    public static final RID A2 = rid(ACCOUNT, 1);
    public static final RID A3 = rid(ACCOUNT, 2);
    public static final RID A4 = rid(ACCOUNT, 3);
    /** Free slot, absent in the baseline. */
    public static final RID A5 = rid(ACCOUNT, 4);
    public static final RID L1 = rid(LOAN, 0);
    public static final RID L2 = rid(LOAN, 1);

    private Fixtures() {
    }

    /** Baseline rows, in RID order. */
    public static Map<RID, List<Value>> baselineRows() {
        Map<RID, List<Value>> rows = new LinkedHashMap<>();
        rows.put(A1, account(1, "Ani", 100.0, "BDG"));
        rows.put(A2, account(2, "Budi", 250.5, "JKT"));
        rows.put(A3, account(3, "Citra", 75.25, "BDG"));
        rows.put(A4, account(4, "Dewi", 1000.0, "SBY"));
        rows.put(L1, loan(1, 1, 500.0));
        rows.put(L2, loan(2, 3, 120.0));
        return rows;
    }

    /** Seeds the baseline into {@code sm} and flushes it, without any log record. */
    public static void loadBaseline(MockStorageManager sm) {
        baselineRows().forEach((rid, values) -> sm.seed(rid.pageId().tableId(), rid, values));
        sm.flushAll();
    }

    public static List<Value> account(int id, String owner, double balance, String branch) {
        return List.of(RecordCodec.integer(id), RecordCodec.varchar(owner),
                RecordCodec.floating(balance), RecordCodec.fixedChar(branch));
    }

    public static List<Value> loan(int id, int accountId, double amount) {
        return List.of(RecordCodec.integer(id), RecordCodec.integer(accountId), RecordCodec.floating(amount));
    }

    public static Value[] values(List<Value> row) {
        return row.toArray(Value[]::new);
    }

    public static RID rid(String table, int slot) {
        return new RID(new PageId(table, 0L), slot);
    }

    /** Starts an expected-state builder from the baseline. */
    public static Expected expectedFromBaseline() {
        Expected expected = new Expected();
        baselineRows().forEach(expected::put);
        return expected;
    }

    /** Builder for the full expected content of every table after a scenario. */
    public static final class Expected {

        private final Map<String, Map<RID, RecordImage>> tables = new TreeMap<>();

        public Expected put(RID rid, List<Value> values) {
            tables.computeIfAbsent(rid.pageId().tableId(), table -> new TreeMap<>(RecordCodec.RID_ORDER))
                    .put(rid, new RecordImage(true, List.copyOf(values)));
            return this;
        }

        public Expected remove(RID rid) {
            Map<RID, RecordImage> rows = tables.get(rid.pageId().tableId());
            if (rows != null) {
                rows.remove(rid);
                if (rows.isEmpty()) {
                    tables.remove(rid.pageId().tableId());
                }
            }
            return this;
        }

        public Map<String, Map<RID, RecordImage>> build() {
            Map<String, Map<RID, RecordImage>> copy = new TreeMap<>();
            tables.forEach((table, rows) -> copy.put(table, new LinkedHashMap<>(rows)));
            return copy;
        }
    }
}
