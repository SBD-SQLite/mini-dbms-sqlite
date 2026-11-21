package sqlike.frm.integration;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import sqlike.ccm.CCMConfig;
import sqlike.ccm.ConcurrencyControlManager;
import sqlike.frm.FailureRecoveryManager;
import sqlike.frm.FrmConfig;
import sqlike.frm.RecoveryState;
import sqlike.frm.adapter.ConcurrencyControlAdapter;
import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.mock.MockStorageManager;
import sqlike.frm.port.CcmPort;
import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.PageId;
import sqlike.shared.ProtocolType;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryResult;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;
import sqlike.shared.Value;

/**
 * Runs FRM with the real CCM ({@link ConcurrencyControlManager}, not {@code MockCCM}) for PRD
 * scenarios 6, 7, and 9: an abort between {@code requestWrite} and {@code beginApply}, the undo of
 * an applied change, and a WAL failure. Every scenario runs under both CCM protocols.
 *
 * <p>The checks cover what FRM owns: the WAL records of each transaction, the FRM state, and the
 * data after a crash and restart (a new SM, FRM, and CCM on the same directory, without a flush).
 * SM is FRM's file-based {@link MockStorageManager}, whose flush and eviction obey the WAL rule,
 * because the real SM does not provide {@code restoreRecord} or snapshots yet.
 *
 * <p>A WAL failure here is a real I/O failure inside FR2: the WAL channel is closed underneath
 * {@link LogManager}, as FR2's own tests do. The error therefore goes through the facade
 * (fail-stop, commit outcome unknown) to CCM, instead of being thrown by a wrapper in front of FRM.
 */
public final class CcmIntegrationTests {

    private static final String TABLE = "account";
    private static final RID R0 = new RID(new PageId(TABLE, 0), 0);
    private static final int START = 100;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    });

    private static int passed;
    private static int failed;

    private interface Scenario {
        void run(Stack stack) throws Exception;
    }

    public static void main(String[] args) {
        java.util.logging.LogManager.getLogManager().reset(); // CCM logs its protocol at INFO
        for (ProtocolType protocol : ProtocolType.values()) {
            run(protocol, "PRD-6 abort between requestWrite and beginApply",
                    CcmIntegrationTests::abortBeforeApply);
            run(protocol, "PRD-7 abort after apply restores the record, frees the lock, survives a crash",
                    CcmIntegrationTests::undoAfterApply);
            run(protocol, "PRD-9 WAL failure on requestWrite stops FRM; restart aborts the transaction",
                    CcmIntegrationTests::walFailureOnWrite);
            run(protocol, "PRD-9 WAL failure on commit leaves the outcome unknown; restart undoes it",
                    CcmIntegrationTests::walFailureOnCommit);
        }
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ scenarios

    /** PRD 6: CCM granted the write and FRM logged it, but the transaction aborts before SM applies it. */
    private static void abortBeforeApply(Stack s) throws Exception {
        long t1 = s.begin();
        long lsn = s.authorize(t1, R0, 999);

        expectStatus(s.ccm.abort(t1), TransactionStatus.ABORTED, "abort T1");
        check(!s.ccm.beginApply(t1, lsn), "beginApply after the abort must be refused, so SM never applies it");
        check(s.balance(R0) == START, "record unchanged, got " + s.balance(R0));
        List<LogRecord> log = s.frm.readLogs(Optional.of(t1));
        check(types(log).equals(List.of(LogType.BEGIN, LogType.UPDATE, LogType.COMPENSATION, LogType.ABORT)),
                "T1 WAL is BEGIN, UPDATE, COMPENSATION, ABORT, got " + types(log));
        check(log.get(2).undoOfLSN().equals(Optional.of(lsn)),
                "COMPENSATION undoes LSN " + lsn + ", got " + log.get(2).undoOfLSN());
        check(s.frm.getState() == RecoveryState.READY, "FRM stays READY, got " + s.frm.getState());
        check(s.ccm.leftoverState().isEmpty(), "CCM released T1, left: '" + s.ccm.leftoverState() + "'");

        long t2 = s.begin();
        s.write(t2, R0, 150);
        expectStatus(s.ccm.commit(t2), TransactionStatus.COMMITTED, "commit T2 on the same record");

        try (Stack after = s.crashAndRestart()) {
            check(after.balance(R0) == 150, "committed T2 is redone after the crash, got " + after.balance(R0));
            List<LogType> again = types(after.frm.readLogs(Optional.of(t1)));
            check(again.equals(types(log)), "recovery adds nothing to the finished T1, got " + again);
        }
    }

    /** PRD 7: the change reached SM and, through an eviction, the disk; the abort writes it back. */
    private static void undoAfterApply(Stack s) throws Exception {
        long t1 = s.begin();
        long lsn = s.write(t1, R0, 40);
        s.sm.evict(TABLE); // STEAL: T1's uncommitted value goes to disk, after its WAL record

        long t2 = 0;
        Future<AccessResponse> waiting = null;
        if (s.protocol == ProtocolType.LOCK_BASED) {
            t2 = s.begin();
            long waiter = t2;
            waiting = POOL.submit(() -> s.ccm.requestWriteAccess(waiter, record(R0)));
            check(!finishesWithin(waiting, 300), "T2 must wait while T1 holds the record");
        }

        expectStatus(s.ccm.abort(t1), TransactionStatus.ABORTED, "abort T1");
        check(s.balance(R0) == START, "FRM wrote the before-image back, got " + s.balance(R0));
        List<LogRecord> log = s.frm.readLogs(Optional.of(t1));
        check(types(log).equals(List.of(LogType.BEGIN, LogType.UPDATE, LogType.COMPENSATION, LogType.ABORT)),
                "T1 WAL is BEGIN, UPDATE, COMPENSATION, ABORT, got " + types(log));
        check(log.get(2).undoOfLSN().equals(Optional.of(lsn)),
                "COMPENSATION undoes LSN " + lsn + ", got " + log.get(2).undoOfLSN());

        if (waiting == null) {
            t2 = s.begin();
            expectGranted(s.ccm.requestWriteAccess(t2, record(R0)), "T2 write access");
        } else {
            expectGranted(waiting.get(5, TimeUnit.SECONDS), "T2 gets the record once T1 has aborted");
        }
        expectStatus(s.ccm.commit(t2), TransactionStatus.COMMITTED, "commit T2");
        check(s.ccm.leftoverState().isEmpty(), "CCM holds nothing, left: '" + s.ccm.leftoverState() + "'");

        // The page on disk still holds T1's 40; only the WAL says T1 was rolled back.
        try (Stack after = s.crashAndRestart()) {
            check(after.balance(R0) == START, "the aborted value on disk is undone after the crash, got "
                    + after.balance(R0));
            List<LogType> again = types(after.frm.readLogs(Optional.of(t1)));
            check(again.equals(types(log)), "recovery does not undo T1 a second time, got " + again);
        }
    }

    /** PRD 9: the WAL cannot store the change, so CCM must not grant the write. */
    private static void walFailureOnWrite(Stack s) throws Exception {
        long t1 = s.begin();
        expectGranted(s.ccm.requestWriteAccess(t1, record(R0)), "T1 write access");
        s.breakWal();

        AccessResponse write = s.ccm.requestWrite(t1, update(R0, START, 5));
        check(write.status() == AccessStatus.ABORTED && write.changeLSN().isEmpty(),
                "write refused without an LSN, got " + write);
        check(code(write.error()) == ErrorCode.WAL_FAILURE, "error WAL_FAILURE, got " + write.error());
        check(s.frm.getState() == RecoveryState.FAILED, "FRM stops (fail-stop), got " + s.frm.getState());
        check(s.balance(R0) == START, "record unchanged, got " + s.balance(R0));

        // FRM refuses the rollback while FAILED, so CCM keeps T1 and its lock until recovery.
        TransactionResponse retry = s.ccm.abort(t1);
        check(retry.status() == TransactionStatus.FAILED, "T1 stays FAILED, got " + retry);
        check(!s.ccm.leftoverState().isEmpty(), "CCM keeps T1's lock until recovery");
        TransactionResponse refused = s.ccm.beginTransaction();
        check(refused.status() != TransactionStatus.ACTIVE && code(refused.error()) == ErrorCode.WAL_FAILURE,
                "no new transaction while the WAL is down, got " + refused);

        try (Stack after = s.crashAndRestart()) {
            List<LogType> t1Log = types(after.frm.readLogs(Optional.of(t1)));
            check(t1Log.equals(List.of(LogType.BEGIN, LogType.ABORT)), "recovery ends T1 with ABORT, got " + t1Log);
            check(after.ccm.leftoverState().isEmpty(), "restarted CCM holds nothing");
            long t3 = after.begin();
            check(t3 > t1, "txIds continue above " + t1 + " after recovery, got " + t3);
            after.write(t3, R0, 7);
            expectStatus(after.ccm.commit(t3), TransactionStatus.COMMITTED, "commit after the restart");
            check(after.balance(R0) == 7, "new value stored, got " + after.balance(R0));
        }
    }

    /** PRD 9: the WAL fails while writing COMMIT, so nobody may report success or undo the change. */
    private static void walFailureOnCommit(Stack s) throws Exception {
        long t1 = s.begin();
        s.write(t1, R0, 70);
        s.sm.evict(TABLE); // T1's uncommitted 70 is on disk before COMMIT is attempted
        s.breakWal();

        TransactionResponse commit = s.ccm.commit(t1);
        check(commit.status() == TransactionStatus.PARTIALLY_COMMITTED,
                "commit is not reported as COMMITTED, got " + commit);
        check(code(commit.error()) == ErrorCode.WAL_FAILURE, "error WAL_FAILURE, got " + commit.error());
        check(s.frm.getState() == RecoveryState.FAILED, "FRM stops (fail-stop), got " + s.frm.getState());
        TransactionResponse abort = s.ccm.abort(t1);
        check(abort.status() == TransactionStatus.PARTIALLY_COMMITTED,
                "CCM must not undo a commit whose outcome is unknown, got " + abort);
        check(!s.ccm.leftoverState().isEmpty(), "CCM keeps T1's lock until recovery decides");
        check(s.ccm.beginTransaction().status() != TransactionStatus.ACTIVE,
                "CCM starts no transaction until recovery");

        try (Stack after = s.crashAndRestart()) {
            List<LogType> t1Log = types(after.frm.readLogs(Optional.of(t1)));
            check(t1Log.equals(List.of(LogType.BEGIN, LogType.UPDATE, LogType.COMPENSATION, LogType.ABORT)),
                    "COMMIT never reached the WAL, so recovery aborts T1, got " + t1Log);
            check(after.balance(R0) == START, "recovery undoes T1's value on disk, got " + after.balance(R0));
            check(after.ccm.leftoverState().isEmpty(), "restarted CCM holds nothing");
            long t2 = after.begin();
            check(t2 > t1, "txIds continue above " + t1 + " after recovery, got " + t2);
            expectStatus(after.ccm.commit(t2), TransactionStatus.COMMITTED, "commit after the restart");
        }
    }

    // ------------------------------------------------------------------ stack

    /** One SM, FRM, and CCM on a directory, started in the PRD §14 order. */
    private static final class Stack implements AutoCloseable {
        final Path dir;
        final ProtocolType protocol;
        final MockStorageManager sm;
        final LogManager log;
        final FailureRecoveryManager frm;
        final ConcurrencyControlManager ccm;
        private boolean closed;

        private Stack(Path dir, ProtocolType protocol, boolean seed) {
            this.dir = dir;
            this.protocol = protocol;
            Path wal = dir.resolve("wal.log");
            this.sm = new MockStorageManager(dir.resolve("data"));
            this.log = new LogManager(wal.toString());
            // FRM and CCM need each other when they are built, so FRM gets CCM's adapter afterwards.
            LateCcmPort ccmPort = new LateCcmPort();
            this.frm = new FailureRecoveryManager(sm, ccmPort,
                    new FrmConfig(wal, Duration.ZERO, Duration.ofSeconds(2)), log);
            sm.setWal(frm::ensureDurable);
            this.ccm = new ConcurrencyControlManager(frm, new CCMConfig(protocol, 5000L, 3, 10L, 2000L));
            ccmPort.delegate = new ConcurrencyControlAdapter(ccm);
            if (seed) {
                sm.seed(TABLE, R0, row(START));
                sm.flushAll();
            }
            // PRD §14: FRM recovers first, then QP passes lastTxId to CCM; this test plays QP.
            RecoveryResult recovered = frm.recover();
            ccm.initAfterRecovery(recovered.lastTxId());
        }

        static Stack open(Path dir, ProtocolType protocol) {
            return new Stack(dir, protocol, true);
        }

        /** Drops the buffer (no flush) and starts SM, FRM, and CCM again on the same files. */
        Stack crashAndRestart() {
            close();
            return new Stack(dir, protocol, false);
        }

        long begin() {
            TransactionResponse response = ccm.beginTransaction();
            expectStatus(response, TransactionStatus.ACTIVE, "begin");
            return response.txId();
        }

        /** QP's steps up to the WAL permit: write access, read the before-image, requestWrite. */
        long authorize(long txId, RID rid, int newBalance) {
            expectGranted(ccm.requestWriteAccess(txId, record(rid)), "write access for T" + txId);
            AccessResponse write = ccm.requestWrite(txId, update(rid, balance(rid), newBalance));
            expectGranted(write, "requestWrite for T" + txId);
            return write.changeLSN().orElseThrow();
        }

        /** The whole QP write: permit, apply guard, SM write stamped with the change LSN. */
        long write(long txId, RID rid, int newBalance) {
            long lsn = authorize(txId, rid, newBalance);
            check(ccm.beginApply(txId, lsn), "beginApply for T" + txId);
            try {
                sm.writeAt(TABLE, rid, image(newBalance), lsn);
            } finally {
                ccm.endApply(txId, lsn);
            }
            return lsn;
        }

        int balance(RID rid) {
            return sm.readAt(TABLE, rid).values().get(1).integerValue().orElseThrow();
        }

        /** Closes the WAL channel under FR2, so the next append fails like a disk failure. */
        void breakWal() throws Exception {
            Field channel = LogManager.class.getDeclaredField("channel");
            channel.setAccessible(true);
            ((FileChannel) channel.get(log)).close();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                frm.close();
            }
        }
    }

    private static final class LateCcmPort implements CcmPort {
        volatile CcmPort delegate;

        @Override
        public void pauseNewTransactions() {
            delegate.pauseNewTransactions();
        }

        @Override
        public boolean waitUntilIdle(long timeoutMs) {
            return delegate.waitUntilIdle(timeoutMs);
        }

        @Override
        public void resumeTransactions() {
            delegate.resumeTransactions();
        }

        @Override
        public void initAfterRecovery(long maxTxId) {
            delegate.initAfterRecovery(maxTxId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void run(ProtocolType protocol, String name, Scenario scenario) {
        String label = "[" + protocol + "] " + name;
        Path dir = null;
        try {
            dir = Files.createTempDirectory("frm-ccm-");
            try (Stack stack = Stack.open(dir, protocol)) {
                scenario.run(stack);
            }
            passed++;
            System.out.println("[PASS] " + label);
        } catch (Throwable error) {
            failed++;
            System.out.println("[FAIL] " + label);
            System.out.println("       " + error);
        } finally {
            deleteQuietly(dir);
        }
    }

    private static ResourceId record(RID rid) {
        return ResourceId.ofRecord(TABLE, rid);
    }

    private static DataChange update(RID rid, int from, int to) {
        return new DataChange(ChangeOperation.UPDATE, TABLE, rid, image(from), image(to));
    }

    private static RecordImage image(int balance) {
        return new RecordImage(true, row(balance));
    }

    private static List<Value> row(int balance) {
        return List.of(integer(1), integer(balance));
    }

    private static Value integer(int value) {
        return new Value(DataType.INTEGER, Optional.of(value), Optional.empty(), Optional.empty());
    }

    private static List<LogType> types(List<LogRecord> records) {
        return records.stream().map(LogRecord::type).toList();
    }

    private static ErrorCode code(Optional<ErrorInfo> error) {
        return error.map(ErrorInfo::code).orElse(null);
    }

    private static boolean finishesWithin(Future<?> future, long millis) {
        try {
            future.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException stillRunning) {
            return false;
        } catch (Exception finished) {
            return true;
        }
    }

    private static void expectStatus(TransactionResponse response, TransactionStatus status, String what) {
        check(response.status() == status, what + ": expected " + status + ", got " + response);
    }

    private static void expectGranted(AccessResponse response, String what) {
        check(response.status() == AccessStatus.GRANTED, what + ": expected GRANTED, got " + response);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temp directory does not change the result
        }
    }
}
