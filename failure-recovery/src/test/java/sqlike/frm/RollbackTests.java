package sqlike.frm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.mock.MockStorageManager;
import sqlike.frm.mock.RecordCodec;
import sqlike.frm.port.SmPort;
import sqlike.frm.rollback.RollbackManager;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.ErrorCode;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.Value;

/**
 * FR3 rollback tests (FR-04). Uses FR2's real {@link LogManager} on a temporary file and
 * {@link MockStorageManager} as SM.
 *
 * <p>Run: compile {@code shared/src/main/java}, {@code failure-recovery/src/main/java}, and this
 * file, then {@code java -cp <out> sqlike.frm.RollbackTests}.
 */
public final class RollbackTests {

    private static final String T = "account";
    private static final RID R1 = rid(0);
    private static final RID R2 = rid(1);
    private static final RID R3 = rid(2);
    private static final RID R4 = rid(3);

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private RollbackTests() {
    }

    public static void main(String[] args) {
        run("UPDATE twice on one record returns to the first before-image", RollbackTests::updateTwice);
        run("INSERT is removed and DELETE comes back on the same RID", RollbackTests::insertAndDelete);
        run("other transactions' records are untouched", RollbackTests::otherTransactionUntouched);
        run("each undo writes a COMPENSATION record and uses its LSN", RollbackTests::usesCompensationLsn);
        run("rollback writes no ABORT, and logAbort is accepted after it", RollbackTests::abortAcceptedAfterRollback);
        run("committed transaction is rejected with INVALID_STATE", RollbackTests::committedRejected);
        run("unknown transaction is rejected with NOT_FOUND", RollbackTests::unknownRejected);
        run("aborted transaction is left unchanged", RollbackTests::abortedIsNoOp);
        run("SM failure is propagated, not swallowed", RollbackTests::smFailurePropagates);
        run("retry after a failure part-way gives the correct result", RollbackTests::retryAfterFailure);
        run("schema change is rejected with UNSUPPORTED_OPERATION", RollbackTests::schemaChangeRejected);
        run("schema change is rejected before any compensation is logged", RollbackTests::schemaChangeRejectedFirst);

        System.out.printf("%n%d passed, %d failed%n", passed, failures.size());
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ cases

    private static void updateTwice() {
        Fixture f = new Fixture();
        f.begin(1);
        f.update(1, R1, row(1, "Ani", 10.0));
        f.update(1, R1, row(1, "Ani", 20.0));
        f.rollback.rollback(1);
        expect(f.value(R1), row(1, "Ani", 100.0), "R1");
    }

    private static void insertAndDelete() {
        Fixture f = new Fixture();
        f.begin(1);
        f.insert(1, R4, row(4, "Dewi", 4.0));
        f.delete(1, R2);
        f.rollback.rollback(1);
        expect(f.sm.readAt(T, R4).exists(), false, "R4 exists");
        expect(f.value(R2), row(2, "Budi", 200.0), "R2");
    }

    private static void otherTransactionUntouched() {
        Fixture f = new Fixture();
        f.begin(1);
        f.update(1, R1, row(1, "Ani", 1.0));
        f.begin(2);
        f.update(2, R3, row(3, "Citra", 33.0));
        f.commit(2);
        f.rollback.rollback(1);
        expect(f.value(R1), row(1, "Ani", 100.0), "R1");
        expect(f.value(R3), row(3, "Citra", 33.0), "R3");
    }

    private static void usesCompensationLsn() {
        Fixture f = new Fixture();
        RecordingSm sm = new RecordingSm(f.sm);
        RollbackManager rollback = new RollbackManager(f.log, sm);
        f.begin(1);
        long first = f.update(1, R1, row(1, "Ani", 1.0));
        long second = f.update(1, R2, row(2, "Budi", 2.0));
        rollback.rollback(1);
        List<LogRecord> clrs = f.records(1, LogType.COMPENSATION);
        expect(clrs.stream().map(r -> r.undoOfLSN().orElseThrow()).toList(), List.of(second, first),
                "COMPENSATION undoOfLSN (newest change first)");
        expect(sm.lsns, clrs.stream().map(LogRecord::lsn).toList(), "LSNs passed to SM");
    }

    private static void abortAcceptedAfterRollback() {
        Fixture f = new Fixture();
        f.begin(1);
        f.update(1, R1, row(1, "Ani", 1.0));
        f.rollback.rollback(1);
        expect(f.records(1, LogType.ABORT).size(), 0, "ABORT records written by rollback");
        f.log.logAbort(1); // what CCM does next; FR2 rejects it if a change has no COMPENSATION
        expect(f.records(1, LogType.ABORT).size(), 1, "ABORT records after logAbort");
    }

    private static void committedRejected() {
        Fixture f = new Fixture();
        f.begin(1);
        f.update(1, R1, row(1, "Ani", 1.0));
        f.commit(1);
        expectError(() -> f.rollback.rollback(1), ErrorCode.INVALID_STATE);
        expect(f.value(R1), row(1, "Ani", 1.0), "R1 keeps the committed value");
    }

    private static void unknownRejected() {
        Fixture f = new Fixture();
        expectError(() -> f.rollback.rollback(99), ErrorCode.NOT_FOUND);
    }

    private static void abortedIsNoOp() {
        Fixture f = new Fixture();
        f.begin(1);
        f.update(1, R1, row(1, "Ani", 1.0));
        f.rollback.rollback(1);
        f.log.logAbort(1);
        f.begin(2);
        f.update(2, R1, row(1, "Ani", 300.0));
        f.commit(2);
        f.rollback.rollback(1);
        expect(f.value(R1), row(1, "Ani", 300.0), "R1 keeps T2's later value");
    }

    private static void smFailurePropagates() {
        Fixture f = new Fixture();
        f.begin(1);
        f.update(1, R1, row(1, "Ani", 1.0));
        FailingSm failing = new FailingSm(f.sm, 1);
        RollbackManager rollback = new RollbackManager(f.log, failing);
        try {
            rollback.rollback(1);
            fail("expected the SM failure to be thrown");
        } catch (IllegalStateException expected) {
            // propagated as-is
        }
    }

    private static void retryAfterFailure() {
        Fixture f = new Fixture();
        f.begin(1);
        f.update(1, R1, row(1, "Ani", 1.0));
        f.update(1, R2, row(2, "Budi", 2.0));
        f.update(1, R3, row(3, "Citra", 3.0));
        FailingSm failing = new FailingSm(f.sm, 2); // second undo write fails
        try {
            new RollbackManager(f.log, failing).rollback(1);
            fail("expected a failure part-way");
        } catch (IllegalStateException expected) {
            // R3 undone, R2 failed, R1 not reached
        }
        f.rollback.rollback(1); // retry with a healthy SM
        expect(f.value(R1), row(1, "Ani", 100.0), "R1");
        expect(f.value(R2), row(2, "Budi", 200.0), "R2");
        expect(f.value(R3), row(3, "Citra", 300.0), "R3");
        expect(f.records(1, LogType.COMPENSATION).size(), 3, "one COMPENSATION per change");
        f.log.logAbort(1);
    }

    private static void schemaChangeRejected() {
        Fixture f = new Fixture();
        f.begin(1);
        f.log.logSchemaChange(1, schemaChange());
        expectError(() -> f.rollback.rollback(1), ErrorCode.UNSUPPORTED_OPERATION);
    }

    private static void schemaChangeRejectedFirst() {
        Fixture f = new Fixture();
        f.begin(1);
        f.log.logSchemaChange(1, schemaChange());
        f.update(1, R1, row(1, "Ani", 1.0));
        expectError(() -> f.rollback.rollback(1), ErrorCode.UNSUPPORTED_OPERATION);
        expect(f.records(1, LogType.COMPENSATION).size(), 0, "COMPENSATION records");
        expect(f.value(R1), row(1, "Ani", 1.0), "R1 untouched");
    }

    private static SchemaChange schemaChange() {
        SchemaImage absent = new SchemaImage("t2", Optional.empty(), List.of(), Optional.empty());
        return new SchemaChange(List.of(absent), List.of(absent));
    }

    // ------------------------------------------------------------------ fixture

    private static final class Fixture {
        final MockStorageManager sm;
        final LogManager log;
        final RollbackManager rollback;

        Fixture() {
            try {
                Path dir = Files.createTempDirectory("frm-rollback-test");
                sm = new MockStorageManager(dir.resolve("data"));
                log = new LogManager(dir.resolve("wal").resolve("frm.wal").toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            sm.setWal(lsn -> { });
            sm.seed(T, R1, row(1, "Ani", 100.0));
            sm.seed(T, R2, row(2, "Budi", 200.0));
            sm.seed(T, R3, row(3, "Citra", 300.0));
            rollback = new RollbackManager(log, sm);
        }

        void begin(long tx) {
            log.logBegin(tx);
        }

        void commit(long tx) {
            log.logCommit(tx);
        }

        long insert(long tx, RID rid, List<Value> values) {
            return write(tx, ChangeOperation.INSERT, rid, new RecordImage(true, values));
        }

        long update(long tx, RID rid, List<Value> values) {
            return write(tx, ChangeOperation.UPDATE, rid, new RecordImage(true, values));
        }

        long delete(long tx, RID rid) {
            return write(tx, ChangeOperation.DELETE, rid, new RecordImage(false, List.of()));
        }

        /** Same order as QP/CCM: before-image, log, then apply. */
        long write(long tx, ChangeOperation op, RID rid, RecordImage after) {
            RecordImage before = sm.readAt(T, rid);
            long lsn = log.logChange(tx, new DataChange(op, T, rid, before, after));
            sm.writeAt(T, rid, after, lsn);
            return lsn;
        }

        List<Value> value(RID rid) {
            return sm.readAt(T, rid).values();
        }

        List<LogRecord> records(long tx, LogType type) {
            return log.readLogs(tx).stream().filter(r -> r.type() == type).toList();
        }
    }

    /** Records the LSN of every write. */
    private static final class RecordingSm extends DelegatingSm {
        final List<Long> lsns = new ArrayList<>();

        RecordingSm(SmPort inner) {
            super(inner);
        }

        @Override
        public void writeAt(String table, RID rid, RecordImage image, long lsn) {
            lsns.add(lsn);
            super.writeAt(table, rid, image, lsn);
        }
    }

    /** Fails on the n-th write (1-based). */
    private static final class FailingSm extends DelegatingSm {
        private final int failOn;
        private int writes = 0;

        FailingSm(SmPort inner, int failOn) {
            super(inner);
            this.failOn = failOn;
        }

        @Override
        public void writeAt(String table, RID rid, RecordImage image, long lsn) {
            if (++writes == failOn) {
                throw new IllegalStateException("simulated SM write failure");
            }
            super.writeAt(table, rid, image, lsn);
        }
    }

    private static class DelegatingSm implements SmPort {
        private final SmPort inner;

        DelegatingSm(SmPort inner) {
            this.inner = inner;
        }

        @Override
        public RecordImage readAt(String table, RID rid) {
            return inner.readAt(table, rid);
        }

        @Override
        public void writeAt(String table, RID rid, RecordImage image, long lsn) {
            inner.writeAt(table, rid, image, lsn);
        }

        @Override
        public void flushAll() {
            inner.flushAll();
        }

        @Override
        public String createSnapshot() {
            return inner.createSnapshot();
        }

        @Override
        public void restoreSnapshot(String snapshotId) {
            inner.restoreSnapshot(snapshotId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static RID rid(int slot) {
        return new RID(new PageId(T, 0L), slot);
    }

    private static List<Value> row(int id, String owner, double balance) {
        return List.of(RecordCodec.integer(id), RecordCodec.varchar(owner), RecordCodec.floating(balance));
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable error) {
            failures.add(name + ": " + error.getMessage());
            System.out.println("[FAIL] " + name + ": " + error.getMessage());
        }
    }

    private static void expect(Object actual, Object expected, String what) {
        if (!actual.equals(expected)) {
            fail(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static void expectError(Runnable action, ErrorCode code) {
        try {
            action.run();
        } catch (FrmException error) {
            expect(error.getCode(), code, "error code");
            return;
        }
        fail("expected FrmException " + code);
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
