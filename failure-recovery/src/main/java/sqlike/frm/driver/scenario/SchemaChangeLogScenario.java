package sqlike.frm.driver.scenario;

import static sqlike.frm.driver.Fixtures.A2;
import static sqlike.frm.driver.Fixtures.A5;
import static sqlike.frm.driver.Fixtures.account;
import static sqlike.frm.driver.Fixtures.values;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import sqlike.frm.FrmException;
import sqlike.frm.driver.Fixtures;
import sqlike.frm.driver.ScenarioContext;
import sqlike.frm.driver.TestScenario;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;

/**
 * DDL logging through the facade (PRD v1.0 §3.5, FR-01..FR-03). QP reaches {@code logSchemaChange}
 * through CCM's {@code requestSchemaWrite}, so the scenario checks what that path depends on: the
 * SCHEMA_CHANGE record is written with the schema images intact, it shares the transaction's LSN
 * order with the data records, and a malformed request is refused before anything is logged.
 *
 * <p>It also pins the limitation down: undo of a schema change needs {@code SmPort.restoreSchema},
 * which does not exist yet, so FR3 refuses the whole rollback with {@code UNSUPPORTED_OPERATION}
 * and writes no COMPENSATION. The caller is told instead of losing the DDL silently.
 */
public class SchemaChangeLogScenario extends TestScenario {

    private static final String NEW_TABLE = "branch_stat";

    public SchemaChangeLogScenario() {
        super("schema-change-log",
                "T1 CREATE TABLE + INSERT commits; T2 DROP TABLE + UPDATE cannot roll back (no restoreSchema)");
    }

    @Override
    public void run(ScenarioContext ctx) {
        // T1: a DDL and a DML record in one transaction, then COMMIT.
        long begin1 = ctx.begin(1);
        SchemaChange created = createTable();
        long ddl1 = ctx.frm().logSchemaChange(1, created);
        long insert1 = ctx.insert(1, A5, values(account(5, "Eka", 42.0, "MLG")));
        ctx.commit(1);

        ctx.check(ddl1 > begin1 && insert1 > ddl1,
                "SCHEMA_CHANGE LSN sits between BEGIN and the INSERT: " + List.of(begin1, ddl1, insert1));
        checkTypes(ctx, 1, List.of(LogType.BEGIN, LogType.SCHEMA_CHANGE, LogType.INSERT, LogType.COMMIT));

        Optional<LogRecord> logged = find(ctx.readLogs(Optional.of(1L)), ddl1);
        ctx.check(logged.isPresent(), "the SCHEMA_CHANGE record is found by the LSN logSchemaChange returned");
        ctx.check(logged.flatMap(LogRecord::schemaChange).equals(Optional.of(created)),
                "the logged SchemaChange equals the one that was passed in: "
                        + logged.flatMap(LogRecord::schemaChange));
        ctx.check(logged.flatMap(LogRecord::change).isEmpty(),
                "a SCHEMA_CHANGE record carries no DataChange");
        ctx.check(ctx.showLogs(Optional.of(1L)).contains(NEW_TABLE),
                "showLogs(T1) mentions the table the DDL created");

        // T2: a DDL plus a data change, and the rollback that FR3 must refuse.
        ctx.begin(2);
        ctx.frm().logSchemaChange(2, dropTable());
        ctx.update(2, A2, values(account(2, "Budi", 77.0, "JKT")));

        Optional<RuntimeException> refused = ctx.expectFailure(
                "rollback of a transaction with a schema change is refused",
                () -> ctx.frm().rollback(2));
        refused.ifPresent(error -> {
            ctx.check(error instanceof FrmException, "rollback refusal is an FrmException: " + error);
            if (error instanceof FrmException frm) {
                ctx.check(frm.getCode() == ErrorCode.UNSUPPORTED_OPERATION,
                        "rollback refusal carries UNSUPPORTED_OPERATION, got " + frm.getCode());
            }
        });
        checkTypes(ctx, 2, List.of(LogType.BEGIN, LogType.SCHEMA_CHANGE, LogType.UPDATE));

        // Invalid requests are refused before a record is written.
        long before = lastLsn(ctx);
        ctx.expectFailure("txId 0 is refused", () -> ctx.frm().logSchemaChange(0, createTable()));
        ctx.expectFailure("a null SchemaChange is refused", () -> ctx.frm().logSchemaChange(3, null));
        ctx.expectFailure("a schema change with no schema on either side is refused",
                () -> ctx.frm().logSchemaChange(3, emptyOnBothSides()));
        ctx.check(lastLsn(ctx) == before, "a refused logSchemaChange appends nothing to the log");
        ctx.check(ctx.readLogs(Optional.of(3L)).isEmpty(), "T3 never reached the log");
    }

    private static SchemaChange createTable() {
        return new SchemaChange(List.of(absent(NEW_TABLE)), List.of(present(NEW_TABLE)));
    }

    private static SchemaChange dropTable() {
        return new SchemaChange(List.of(present(Fixtures.LOAN)), List.of(absent(Fixtures.LOAN)));
    }

    /** Rejected by the facade: neither side describes a schema, so the record would mean nothing. */
    private static SchemaChange emptyOnBothSides() {
        return new SchemaChange(List.of(absent(NEW_TABLE)), List.of(absent(NEW_TABLE)));
    }

    private static SchemaImage absent(String table) {
        return new SchemaImage(table, Optional.empty(), List.of(), Optional.empty());
    }

    private static SchemaImage present(String table) {
        TableSchema schema = new TableSchema(table, table, List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("branch", DataType.CHAR, Optional.of(3)),
                new ColumnSchema("total", DataType.FLOAT, Optional.empty())));
        return new SchemaImage(table, Optional.of(schema), List.of(), Optional.empty());
    }

    private static long lastLsn(ScenarioContext ctx) {
        List<LogRecord> all = ctx.readLogs(Optional.empty());
        return all.isEmpty() ? 0L : all.get(all.size() - 1).lsn();
    }

    private static void checkTypes(ScenarioContext ctx, long txId, List<LogType> expected) {
        List<LogType> actual = new ArrayList<>();
        for (LogRecord record : ctx.readLogs(Optional.of(txId))) {
            actual.add(record.type());
        }
        ctx.check(actual.equals(expected), "T" + txId + " record types " + actual + ", expected " + expected);
    }

    private static Optional<LogRecord> find(List<LogRecord> records, long lsn) {
        return records.stream().filter(record -> record.lsn() == lsn).findFirst();
    }

    @Override
    public Map<String, Map<RID, RecordImage>> expected() {
        // T1 committed its INSERT. T2's UPDATE stays applied: the rollback was refused, so FRM
        // undid nothing, and no recovery runs in an in-process scenario.
        return Fixtures.expectedFromBaseline()
                .put(A5, account(5, "Eka", 42.0, "MLG"))
                .put(A2, account(2, "Budi", 77.0, "JKT"))
                .build();
    }
}
