package sqlike.frm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.maintenance.MaintenanceService;
import sqlike.frm.mock.MockCCM;
import sqlike.frm.mock.MockStorageManager;
import sqlike.frm.mock.RecordCodec;
import sqlike.frm.pitr.PitrMarker;
import sqlike.shared.ChangeOperation;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;

/** Crash recovery and PITR with SCHEMA_CHANGE records, and the startup resume of an interrupted PITR. */
public class DdlRecoveryTests {

    private static int passed;
    private static int failed;

    private static final String LOAN = "loan";
    private static final String ACCOUNT = "account";

    public static void main(String[] args) {
        run("crash recovery redoes a committed CREATE TABLE that was not flushed", DdlRecoveryTests::redoCommittedCreate);
        run("crash recovery undoes an unfinished CREATE TABLE that was flushed", DdlRecoveryTests::undoUnfinishedCreate);
        run("crash recovery undoes an unfinished DROP TABLE and keeps committed rows", DdlRecoveryTests::undoUnfinishedDrop);
        run("PITR replays CREATE TABLE before the target and skips DROP TABLE after it", DdlRecoveryTests::pitrReplaysSchema);
        run("startup finishes a PITR that a crash interrupted", DdlRecoveryTests::startupResumesPitr);

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void redoCommittedCreate() throws Exception {
        Path dir = Files.createTempDirectory("frm-ddl-");
        FailureRecoveryManager frm = open(new MockStorageManager(dir), dir);
        frm.recover();
        frm.logBegin(1);
        frm.logSchemaChange(1, create(LOAN));
        frm.logCommit(1);
        frm.close(); // crash: SM never received or flushed the new table

        MockStorageManager sm = new MockStorageManager(dir);
        FailureRecoveryManager restarted = open(sm, dir);
        restarted.recover();
        check(sm.getSchemaImage(LOAN).flatMap(SchemaImage::schema).isPresent(),
                "the committed table is back in the catalog after redo");
        restarted.close();
    }

    private static void undoUnfinishedCreate() throws Exception {
        Path dir = Files.createTempDirectory("frm-ddl-");
        MockStorageManager sm = new MockStorageManager(dir);
        FailureRecoveryManager frm = open(sm, dir);
        frm.recover();
        frm.logBegin(1);
        long lsn = frm.logSchemaChange(1, create(LOAN));
        sm.restoreSchema(present(LOAN), lsn);
        sm.writeAt(LOAN, rid(LOAN, 0), row(7), frm.logChange(1, insert(LOAN, 0, 7)));
        sm.flushAll();
        frm.close(); // crash before COMMIT

        MockStorageManager restartedSm = new MockStorageManager(dir);
        FailureRecoveryManager restarted = open(restartedSm, dir);
        restarted.recover();
        List<LogRecord> logs = restarted.readLogs(Optional.of(1L));
        check(!restartedSm.tableNames().contains(LOAN), "the unfinished table is removed");
        check(count(logs, LogType.COMPENSATION) == 2 && count(logs, LogType.ABORT) == 1,
                "the schema and data changes each get one CLR, then one ABORT");
        restarted.close();
    }

    private static void undoUnfinishedDrop() throws Exception {
        Path dir = Files.createTempDirectory("frm-ddl-");
        MockStorageManager sm = new MockStorageManager(dir);
        FailureRecoveryManager frm = open(sm, dir);
        frm.recover();
        frm.logBegin(1);
        sm.restoreSchema(present(ACCOUNT), frm.logSchemaChange(1, create(ACCOUNT)));
        sm.writeAt(ACCOUNT, rid(ACCOUNT, 0), row(5), frm.logChange(1, insert(ACCOUNT, 0, 5)));
        frm.logCommit(1);
        frm.logBegin(2);
        sm.restoreSchema(absent(ACCOUNT), frm.logSchemaChange(2, drop(ACCOUNT)));
        sm.flushAll();
        frm.close(); // crash: the DROP reached disk, its COMMIT did not

        MockStorageManager restartedSm = new MockStorageManager(dir);
        FailureRecoveryManager restarted = open(restartedSm, dir);
        restarted.recover();
        check(restartedSm.getSchemaImage(ACCOUNT).flatMap(SchemaImage::schema).isPresent(),
                "the dropped table is back in the catalog");
        check(row(5).equals(restartedSm.readAt(ACCOUNT, rid(ACCOUNT, 0))),
                "the committed row of the table is back after undo then redo");
        restarted.close();
    }

    private static void pitrReplaysSchema() throws Exception {
        Path dir = Files.createTempDirectory("frm-ddl-");
        MockStorageManager sm = new MockStorageManager(dir);
        FailureRecoveryManager frm = open(sm, dir);
        frm.recover(); // baseline snapshot before any table exists
        frm.logBegin(1);
        sm.restoreSchema(present(LOAN), frm.logSchemaChange(1, create(LOAN)));
        sm.writeAt(LOAN, rid(LOAN, 0), row(9), frm.logChange(1, insert(LOAN, 0, 9)));
        frm.logCommit(1);
        frm.logBegin(2);
        sm.restoreSchema(absent(LOAN), frm.logSchemaChange(2, drop(LOAN)));
        frm.logCommit(2);
        check(!sm.tableNames().contains(LOAN), "the table is dropped before PITR");

        frm.recoverTo(new RecoveryTarget(RecoveryTargetKind.TRANSACTION, Optional.empty(), Optional.of(1L)));
        check(sm.getSchemaImage(LOAN).flatMap(SchemaImage::schema).isPresent(),
                "PITR to T1 brings the table back");
        check(row(9).equals(sm.readAt(LOAN, rid(LOAN, 0))), "PITR to T1 brings the row of T1 back");
        frm.close();
    }

    private static void startupResumesPitr() throws Exception {
        Path dir = Files.createTempDirectory("frm-ddl-");
        MockStorageManager sm = new MockStorageManager(dir);
        FailureRecoveryManager frm = open(sm, dir);
        frm.recover();
        sm.restoreSchema(present(ACCOUNT), 0);
        frm.logBegin(1);
        sm.writeAt(ACCOUNT, rid(ACCOUNT, 0), row(1), frm.logChange(1, insert(ACCOUNT, 0, 1)));
        frm.logCommit(1);
        frm.logBegin(2);
        sm.writeAt(ACCOUNT, rid(ACCOUNT, 0), row(2), frm.logChange(2, new DataChange(
                ChangeOperation.UPDATE, ACCOUNT, rid(ACCOUNT, 0), row(1), row(2))));
        frm.logCommit(2);
        sm.flushAll();
        frm.close();

        // Crash right after PITR to T1 flagged itself, before any data was replaced.
        Path markerPath = dir.resolve(MaintenanceService.PITR_MARKER_FILE);
        new PitrMarker(markerPath).write(
                new RecoveryTarget(RecoveryTargetKind.TRANSACTION, Optional.empty(), Optional.of(1L)));

        MockStorageManager restartedSm = new MockStorageManager(dir);
        FailureRecoveryManager restarted = open(restartedSm, dir);
        restarted.recover();
        check(row(1).equals(restartedSm.readAt(ACCOUNT, rid(ACCOUNT, 0))),
                "startup finishes the restore to T1 before serving transactions");
        check(!Files.exists(markerPath), "the marker is removed after the resumed restore");
        restarted.close();

        MockStorageManager againSm = new MockStorageManager(dir);
        FailureRecoveryManager again = open(againSm, dir);
        again.recover();
        check(row(1).equals(againSm.readAt(ACCOUNT, rid(ACCOUNT, 0))),
                "a later restart keeps the restored state");
        again.close();
    }

    // ------------------------------------------------------------------ helpers

    private static FailureRecoveryManager open(MockStorageManager sm, Path dir) {
        FailureRecoveryManager frm = new FailureRecoveryManager(
                sm, new MockCCM(), dir.resolve("wal.log").toString(), java.time.Duration.ZERO);
        sm.setWal(frm::ensureDurable);
        return frm;
    }

    private static SchemaChange create(String table) {
        return new SchemaChange(List.of(absent(table)), List.of(present(table)));
    }

    private static SchemaChange drop(String table) {
        return new SchemaChange(List.of(present(table)), List.of(absent(table)));
    }

    private static SchemaImage absent(String table) {
        return new SchemaImage(table, Optional.empty(), List.of(), Optional.empty());
    }

    private static SchemaImage present(String table) {
        TableSchema schema = new TableSchema(table, table,
                List.of(new ColumnSchema("v", DataType.INTEGER, Optional.empty())));
        return new SchemaImage(table, Optional.of(schema), List.of(), Optional.empty());
    }

    private static RID rid(String table, int slot) {
        return new RID(new PageId(table, 0), slot);
    }

    private static RecordImage row(int value) {
        return new RecordImage(true, List.of(RecordCodec.integer(value)));
    }

    private static DataChange insert(String table, int slot, int value) {
        return new DataChange(ChangeOperation.INSERT, table, rid(table, slot),
                new RecordImage(false, List.of()), row(value));
    }

    private static long count(List<LogRecord> records, LogType type) {
        return records.stream().filter(record -> record.type() == type).count();
    }

    private static void run(String name, ThrowingTest test) {
        System.out.println("== " + name);
        try {
            test.run();
        } catch (Exception | AssertionError error) {
            check(false, "unexpected " + error);
            error.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  [PASS] " : "  [FAIL] ") + message);
        if (condition) {
            passed++;
        } else {
            failed++;
        }
    }

    @FunctionalInterface
    private interface ThrowingTest {
        void run() throws Exception;
    }
}
