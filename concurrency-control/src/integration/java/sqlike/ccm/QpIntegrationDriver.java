package sqlike.ccm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.FailureRecoveryManager;
import sqlike.frm.FrmConfig;
import sqlike.frm.adapter.ConcurrencyControlAdapter;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.mock.MockStorageManager;
import sqlike.frm.port.CcmPort;
import sqlike.qp.QpException;
import sqlike.qp.port.ConcurrencyAdapter;
import sqlike.qp.port.QpContracts.AccessDecision;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.ChangeId;
import sqlike.qp.port.QpContracts.ChangeRecord;
import sqlike.qp.port.QpContracts.RecoveryReport;
import sqlike.qp.port.QpContracts.ResourceKey;
import sqlike.qp.port.QpContracts.SchemaChangeRecord;
import sqlike.qp.port.QpContracts.SessionId;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.qp.port.QpContracts.TransactionState;
import sqlike.qp.port.QpContracts.TxId;
import sqlike.qp.port.QpContracts.WriteLease;
import sqlike.qp.port.QpContracts.WritePermit;
import sqlike.shared.AccessStatus;
import sqlike.shared.ChangeOperation;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.PageId;
import sqlike.shared.ProtocolType;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryResult;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;

/**
 * Integration driver: the real QP {@link ConcurrencyAdapter} on top of the real CCM, the real FRM
 * (WAL, rollback, recovery, checkpoint) and FRM's own MockStorageManager as the data store.
 *
 * <p>Startup follows PRD section 14: FRM.recover(), then QP initialize(report) which calls
 * CCM.initAfterRecovery(lastTxId). Writes follow the two-stage contract: requestWriteAccess, then
 * requestWrite (WAL), then beginApply, SM write, endApply. Every scenario runs on a fresh data
 * directory and must leave no lock or protocol data behind (ConcurrencyControlManager.leftoverState).
 *
 * <p>FRM and CCM need each other at construction time; the driver breaks the cycle with a
 * late-bound CcmPort. FaultyFrm only adds one-shot failure injection in front of the real FRM.
 */
public final class QpIntegrationDriver {

    private static int passed;
    private static int failed;
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private interface Scenario {
        void run(Rig rig) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        LogManager.getLogManager().reset();

        for (ProtocolType protocol : ProtocolType.values()) {
            String p = "[" + protocol + "] ";
            run(p + "INT-01 Alur tulis QP penuh: akses, izin WAL, apply ke SM, commit", protocol, QpIntegrationDriver::happyPath);
            run(p + "INT-02 Dua pembaca berbagi akses", protocol, QpIntegrationDriver::readSharing);
            run(p + "INT-03 Penulis kedua menunggu sampai penulis pertama commit", protocol, QpIntegrationDriver::writeBlocksUntilCommit);
            run(p + "INT-04 Upgrade READ ke WRITE oleh satu transaksi (CC-07)", protocol, QpIntegrationDriver::upgradeSingleTx);
            run(p + "INT-06 Abort antara izin tulis dan apply: apply ditolak, data utuh", protocol, QpIntegrationDriver::abortBeforeApply);
            run(p + "INT-07 Abort setelah apply: FRM mengundo data di SM", protocol, QpIntegrationDriver::abortAfterApply);
            run(p + "INT-08 Kegagalan WAL pada izin tulis membatalkan transaksi", protocol, QpIntegrationDriver::walFailureOnChange);
            run(p + "INT-09 Kegagalan logCommit: bukan COMMITTED, lock ditahan sampai recovery", protocol, QpIntegrationDriver::commitUnknown);
            run(p + "INT-10 Perubahan skema (DDL) lewat requestSchemaWrite dan WAL FRM", protocol, QpIntegrationDriver::schemaChange);
            run(p + "INT-12 Restart: FRM.recover, lalu initAfterRecovery; redo commit, undo aktif", protocol, QpIntegrationDriver::restartRecovery);
            run(p + "INT-13 Checkpoint FRM: timeout idle tidak membocorkan gerbang, lalu sukses", protocol, QpIntegrationDriver::checkpointTimeoutThenSuccess);
            run(p + "INT-14 Akses setelah ABORT ditolak", protocol, QpIntegrationDriver::accessAfterAbort);
            run(p + "INT-15 Delapan client menaikkan counter: tanpa lost update, serializable", protocol, QpIntegrationDriver::concurrentCounter);
        }
        run("[LOCK_BASED] INT-05 Deadlock: korban termuda ABORTED dan datanya diundo", ProtocolType.LOCK_BASED, QpIntegrationDriver::deadlock);
        run("[TIMESTAMP_BASED] INT-11 Tulis oleh transaksi lebih tua ditolak", ProtocolType.TIMESTAMP_BASED, QpIntegrationDriver::timestampViolation);

        knownGapDdlRollback();

        System.out.println();
        System.out.println("Total: " + passed + "/" + (passed + failed) + " skenario integrasi LULUS");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- scenarios

    private static void happyPath(Rig r) {
        TxId tx = r.begin();
        write(r, tx, 1, "items", 0, 11);
        expect(r.adapter.commit(tx) == TransactionState.COMMITTED, "commit COMMITTED");
        expect(r.value("items", 0) == 11, "SM berisi nilai baru, dapat " + r.value("items", 0));
        expect(r.logTypes(tx).equals(List.of(LogType.BEGIN, LogType.UPDATE, LogType.COMMIT)),
                "urutan WAL BEGIN, UPDATE, COMMIT, dapat " + r.logTypes(tx));
    }

    private static void readSharing(Rig r) {
        TxId t1 = r.begin();
        TxId t2 = r.begin();
        expect(acquire(r.adapter, t1, "items", AccessMode.READ).status() == AccessStatus.GRANTED, "T1 READ");
        Future<AccessDecision> second = POOL.submit(() -> acquire(r.adapter, t2, "items", AccessMode.READ));
        expect(get(second, 2).status() == AccessStatus.GRANTED, "T2 READ tidak menunggu T1");
        expect(r.adapter.commit(t1) == TransactionState.COMMITTED, "T1 commit");
        expect(r.adapter.commit(t2) == TransactionState.COMMITTED, "T2 commit");
    }

    private static void writeBlocksUntilCommit(Rig r) {
        TxId t1 = r.begin();
        TxId t2 = r.begin();
        expect(acquire(r.adapter, t1, "items", AccessMode.WRITE).status() == AccessStatus.GRANTED, "T1 WRITE");
        Future<AccessDecision> second = POOL.submit(() -> acquire(r.adapter, t2, "items", AccessMode.WRITE));
        expect(!isDone(second, 400), "T2 WRITE harus menunggu selama T1 menahan penulisan");
        expect(r.adapter.commit(t1) == TransactionState.COMMITTED, "T1 commit");
        expect(get(second, 5).status() == AccessStatus.GRANTED, "T2 mendapat akses setelah T1 commit");
        expect(r.adapter.commit(t2) == TransactionState.COMMITTED, "T2 commit");
    }

    private static void upgradeSingleTx(Rig r) {
        TxId tx = r.begin();
        expect(acquire(r.adapter, tx, "items", AccessMode.READ).status() == AccessStatus.GRANTED, "READ");
        Future<AccessDecision> upgrade = POOL.submit(() -> acquire(r.adapter, tx, "items", AccessMode.WRITE));
        expect(get(upgrade, 3).status() == AccessStatus.GRANTED, "upgrade S ke X oleh pemilik tunggal");
        expect(r.adapter.commit(tx) == TransactionState.COMMITTED, "commit");
    }

    private static void deadlock(Rig r) {
        TxId t1 = r.begin();
        TxId t2 = r.begin();
        write(r, t1, 1, "A", 0, 101);
        write(r, t2, 1, "B", 0, 202);
        Future<Boolean> f1 = POOL.submit(() -> grantedOrAborted(r.adapter, t1, "B"));
        Future<Boolean> f2 = POOL.submit(() -> grantedOrAborted(r.adapter, t2, "A"));
        expect(get(f1, 10), "T1 (tua) akhirnya mendapat B");
        expect(!get(f2, 10), "T2 (termuda) menjadi korban deadlock");
        expect(r.adapter.getState(t2) == TransactionState.ABORTED, "state adapter T2 ABORTED");
        expect(r.value("B", 0) == 2, "FRM mengundo tulisan korban di SM, B = " + r.value("B", 0));
        expect(r.logTypes(t2).contains(LogType.ABORT), "WAL mencatat ABORT korban");
        expect(r.adapter.commit(t1) == TransactionState.COMMITTED, "T1 commit");
        expect(r.value("A", 0) == 101, "tulisan T1 bertahan");
    }

    private static void abortBeforeApply(Rig r) {
        TxId tx = r.begin();
        acquire(r.adapter, tx, "items", AccessMode.WRITE);
        WritePermit permit = r.adapter.authorizeChange(change(r, tx, 1, "items", 0, 77));
        expect(r.adapter.abort(tx, "test") == TransactionState.ABORTED, "abort ABORTED");
        expectThrows(() -> r.adapter.acquireApplyLease(permit), "lease apply setelah abort ditolak");
        expect(r.value("items", 0) == 10, "SM tidak berubah, dapat " + r.value("items", 0));
        expect(r.logTypes(tx).contains(LogType.ABORT), "WAL mencatat ABORT");
    }

    private static void abortAfterApply(Rig r) {
        TxId tx = r.begin();
        write(r, tx, 1, "items", 0, 55);
        expect(r.value("items", 0) == 55, "tulisan sudah masuk SM");
        expect(r.adapter.abort(tx, "test") == TransactionState.ABORTED, "abort ABORTED");
        expect(r.value("items", 0) == 10, "FRM mengembalikan before-image, dapat " + r.value("items", 0));
    }

    private static void walFailureOnChange(Rig r) {
        TxId tx = r.begin();
        acquire(r.adapter, tx, "items", AccessMode.WRITE);
        r.frm.failOnce("logChange");
        try {
            r.adapter.authorizeChange(change(r, tx, 1, "items", 0, 5));
            expect(false, "authorizeChange harus gagal saat WAL gagal");
        } catch (QpException e) {
            expect(e.getErrorInfo().code() == ErrorCode.WAL_FAILURE, "kode WAL_FAILURE, dapat " + e.getErrorInfo().code());
        }
        expect(r.adapter.getState(tx) == TransactionState.ABORTED, "transaksi ABORTED");
        expect(r.value("items", 0) == 10, "SM tidak berubah");
    }

    private static void commitUnknown(Rig r) {
        TxId tx = r.begin();
        write(r, tx, 1, "items", 0, 12);
        r.frm.failOnce("logCommit");
        TransactionState state;
        try {
            state = r.adapter.commit(tx);
        } catch (RuntimeException e) {
            state = r.adapter.getState(tx);
        }
        expect(state != TransactionState.COMMITTED, "kegagalan logCommit bukan sukses palsu, dapat " + state);
        expect(!r.logTypes(tx).contains(LogType.COMMIT), "WAL tidak berisi COMMIT");
        expect(!r.ccm.leftoverState().isEmpty(), "lock sengaja ditahan sampai recovery memutuskan");
        // Recovery decides: CCM is reset by initAfterRecovery and nothing may be left behind.
        r.ccm.initAfterRecovery(tx.value());
    }

    private static void schemaChange(Rig r) {
        TxId tx = r.begin();
        WritePermit permit = r.adapter.authorizeSchemaChange(
                new SchemaChangeRecord(new ChangeId(tx, 1), tx, createTable("newtable")));
        expect(permit.lsn().value() > 0, "izin DDL membawa LSN dari FRM");
        expect(r.logTypes(tx).contains(LogType.SCHEMA_CHANGE), "WAL FRM mencatat SCHEMA_CHANGE: " + r.logTypes(tx));
        try (WriteLease lease = r.adapter.acquireApplyLease(permit)) {
            r.adapter.confirmApplied(permit.changeId());
        }
        expect(r.adapter.commit(tx) == TransactionState.COMMITTED, "commit DDL");
    }

    private static void timestampViolation(Rig r) {
        TxId older = r.begin();
        TxId younger = r.begin();
        write(r, younger, 1, "items", 0, 20);
        expect(r.adapter.commit(younger) == TransactionState.COMMITTED, "younger commit");
        boolean rejected;
        try {
            rejected = acquire(r.adapter, older, "items", AccessMode.WRITE).status() == AccessStatus.ABORTED;
        } catch (QpException e) {
            rejected = e.getErrorInfo().code() == ErrorCode.TRANSACTION_ABORTED;
        }
        expect(rejected, "tulis oleh transaksi lebih tua ditolak (TRANSACTION_ABORTED)");
        expect(r.adapter.getState(older) == TransactionState.ABORTED, "state adapter older ABORTED");
        expect(r.value("items", 0) == 20, "nilai younger bertahan");
    }

    /**
     * Crash simulation: no close() on the old components. T1 commits, T2 writes and the dirty page
     * is flushed to disk without T2 committing. A new FRM/CCM stack on the same directory must redo
     * T1, undo T2, report the highest txId, and CCM must continue above it.
     */
    private static void restartRecovery(Rig r) throws Exception {
        TxId t1 = r.begin();
        write(r, t1, 1, "items", 0, 11);
        expect(r.adapter.commit(t1) == TransactionState.COMMITTED, "T1 commit");
        TxId t2 = r.begin();
        write(r, t2, 1, "items", 0, 99);
        r.sm.flushAll();
        r.cleanChecked = true;
        // The process "dies": FRM.close() only releases the WAL file lock, it writes no checkpoint,
        // so the new stack sees exactly what a killed process would leave on disk.
        r.real.close();

        Rig restarted = Rig.open(r.dir, r.protocol, false);
        expect(restarted.recovery.lastTxId() >= t2.value(), "lastTxId dari recovery >= " + t2.value()
                + ", dapat " + restarted.recovery.lastTxId());
        expect(restarted.value("items", 0) == 11, "redo T1 dan undo T2, items = " + restarted.value("items", 0));
        TxId next = restarted.begin();
        expect(next.value() > t2.value(), "txId pertama setelah recovery > " + t2.value() + ", dapat " + next.value());
        write(restarted, next, 1, "items", 0, 12);
        expect(restarted.adapter.commit(next) == TransactionState.COMMITTED, "transaksi baru commit");
        expect(restarted.value("items", 0) == 12, "tulisan setelah recovery masuk");
        assertClean(restarted);
    }

    private static void checkpointTimeoutThenSuccess(Rig r) {
        TxId active = r.begin();
        int before = r.checkpointRecords();
        Future<Object> checkpoint = POOL.submit(() -> {
            r.real.checkpoint();
            return null;
        });
        boolean failedAsExpected;
        try {
            checkpoint.get(8, TimeUnit.SECONDS);
            failedAsExpected = false;
        } catch (TimeoutException e) {
            throw new AssertionError("checkpoint tidak selesai dalam 8 detik: gerbang CCM menggantung?");
        } catch (Exception e) {
            failedAsExpected = true;
        }
        expect(failedAsExpected, "checkpoint dibatalkan karena ada transaksi aktif");
        expect(r.checkpointRecords() == before, "tidak ada marker checkpoint untuk checkpoint yang dibatalkan");
        Future<TxId> another = POOL.submit(r::begin);
        expect(get(another, 3) != null, "begin tidak terblokir: gerbang tidak bocor");
        expect(r.adapter.commit(active) == TransactionState.COMMITTED, "transaksi aktif commit");
        TxId second = get(another, 3);
        expect(r.adapter.commit(second) == TransactionState.COMMITTED, "transaksi kedua commit");
        r.real.checkpoint();
        expect(r.checkpointRecords() == before + 1, "checkpoint sukses mencatat satu marker");
    }

    private static void accessAfterAbort(Rig r) {
        TxId tx = r.begin();
        r.adapter.abort(tx, "test");
        expectThrows(() -> r.adapter.acquireAccess(tx, table("items"), AccessMode.READ), "READ setelah abort ditolak");
        expectThrows(() -> r.adapter.authorizeChange(change(r, tx, 1, "items", 0, 1)), "WRITE setelah abort ditolak");
    }

    /**
     * Each client reads the counter, pauses, then writes counter+1 through the full QP write path.
     * Without correct isolation updates are lost; with it the final value equals the client count,
     * and the committed schedule must be conflict serializable.
     */
    private static void concurrentCounter(Rig r) throws Exception {
        final int clients = 8;
        AtomicInteger retries = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            futures.add(POOL.submit(() -> {
                start.await();
                while (true) {
                    TxId tx = r.begin();
                    try {
                        if (!granted(r.adapter, tx, "counter", AccessMode.READ)) {
                            throw new QpException(ErrorCode.TRANSACTION_ABORTED, "read denied");
                        }
                        int seen = r.value("counter", 0);
                        Thread.sleep(5);
                        if (!granted(r.adapter, tx, "counter", AccessMode.WRITE)) {
                            throw new QpException(ErrorCode.TRANSACTION_ABORTED, "write denied");
                        }
                        WritePermit permit = r.adapter.authorizeChange(change(r, tx, 1, "counter", 0, seen + 1, seen));
                        try (WriteLease lease = r.adapter.acquireApplyLease(permit)) {
                            r.sm.writeAt("counter", rid("counter", 0), intImage(seen + 1), permit.lsn().value());
                            r.adapter.confirmApplied(permit.changeId());
                        }
                        if (r.adapter.commit(tx) == TransactionState.COMMITTED) {
                            return null;
                        }
                    } catch (QpException e) {
                        if (e.getErrorInfo().code() != ErrorCode.TRANSACTION_ABORTED) {
                            throw e;
                        }
                    }
                    int attempt = retries.incrementAndGet();
                    if (r.adapter.getState(tx) != TransactionState.ABORTED) {
                        r.adapter.abort(tx, "retry");
                    }
                    // Random backoff: without it, timestamp ordering keeps aborting the same clients
                    // because every retry gets a newer timestamp than its competitors.
                    Thread.sleep(ThreadLocalRandom.current().nextInt(5, 25 + 5 * Math.min(attempt, 20)));
                }
            }));
        }
        start.countDown();
        for (Future<Void> f : futures) {
            get(f, 60);
        }
        expect(r.value("counter", 0) == clients, "counter = " + r.value("counter", 0) + ", harus " + clients
                + " (retry=" + retries.get() + ")");
        expect(new SerializabilityVerifier().isConflictSerializable(r.ccm.getRecorder().committedSchedule()),
                "jadwal committed conflict serializable");
    }

    /**
     * Known gap, reported but not counted: FRM refuses to roll back a transaction that holds a
     * SCHEMA_CHANGE (SmPort has no restoreSchema), so CCM keeps that transaction FAILED with its locks.
     */
    private static void knownGapDdlRollback() {
        String outcome;
        try (Rig r = Rig.open(tempDir(), ProtocolType.LOCK_BASED, true)) {
            TxId tx = r.begin();
            WritePermit permit = r.adapter.authorizeSchemaChange(
                    new SchemaChangeRecord(new ChangeId(tx, 1), tx, createTable("gap")));
            try (WriteLease lease = r.adapter.acquireApplyLease(permit)) {
                r.adapter.confirmApplied(permit.changeId());
            }
            TransactionState state;
            try {
                state = r.adapter.abort(tx, "test");
            } catch (RuntimeException e) {
                state = r.adapter.getState(tx);
            }
            outcome = "abort transaksi DDL -> " + state + ", sisa CCM: '" + r.ccm.leftoverState() + "'";
        } catch (Exception e) {
            outcome = "tidak bisa diperiksa: " + e;
        }
        System.out.println("[CELAH ] FRM tidak bisa rollback DDL (butuh SmPort.restoreSchema): " + outcome);
    }

    // ---------------------------------------------------------------- rig

    /** One FRM, SM and CCM stack on a data directory, wired the way QP's bootstrap would. */
    private static final class Rig implements AutoCloseable {
        final Path dir;
        final ProtocolType protocol;
        final MockStorageManager sm;
        final FailureRecoveryManager real;
        final FaultyFrm frm;
        final ConcurrencyControlManager ccm;
        final ConcurrencyAdapter adapter;
        final RecoveryResult recovery;
        boolean cleanChecked;

        private Rig(Path dir, ProtocolType protocol, boolean seed) {
            this.dir = dir;
            this.protocol = protocol;
            this.sm = new MockStorageManager(dir);
            if (seed) {
                sm.seed("items", rid("items", 0), intImage(10).values());
                sm.seed("A", rid("A", 0), intImage(1).values());
                sm.seed("B", rid("B", 0), intImage(2).values());
                sm.seed("counter", rid("counter", 0), intImage(0).values());
            }
            LateCcmPort late = new LateCcmPort();
            FrmConfig config = new FrmConfig(dir.resolve("wal.log"), Duration.ZERO, Duration.ofSeconds(1));
            this.real = new FailureRecoveryManager(sm, late, config);
            this.sm.setWal(real::ensureDurable);
            this.frm = new FaultyFrm(real);
            this.ccm = new ConcurrencyControlManager(frm, new CCMConfig(protocol, 5000L));
            late.delegate = new ConcurrencyControlAdapter(ccm);
            this.adapter = new ConcurrencyAdapter(ccm, frm);
            // PRD section 14 startup order: FRM.recover, then QP initialize -> CCM.initAfterRecovery.
            this.recovery = real.recover();
            this.adapter.initialize(new RecoveryReport(true, recovery.lastTxId()));
        }

        static Rig open(Path dir, ProtocolType protocol, boolean seed) {
            return new Rig(dir, protocol, seed);
        }

        TxId begin() {
            return adapter.begin(new SessionId(UUID.randomUUID()));
        }

        int value(String table, int slot) {
            return sm.readAt(table, rid(table, slot)).values().get(0).integerValue().orElseThrow();
        }

        List<LogType> logTypes(TxId tx) {
            List<LogType> types = new ArrayList<>();
            for (LogRecord record : real.readLogs(Optional.of(tx.value()))) {
                types.add(record.type());
            }
            return types;
        }

        int checkpointRecords() {
            int count = 0;
            for (LogRecord record : real.readLogs(Optional.empty())) {
                if (record.type() == LogType.CHECKPOINT) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public void close() {
            real.close();
        }
    }

    /** CCM and FRM need each other at construction; the CCM adapter is plugged in afterwards. */
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

    /** The real FRM with one-shot failure injection, standing where CCM expects IFailureRecoveryManager. */
    private static final class FaultyFrm implements IFailureRecoveryManager {
        private final FailureRecoveryManager real;
        private final Set<String> failNext = new HashSet<>();

        FaultyFrm(FailureRecoveryManager real) {
            this.real = real;
        }

        synchronized void failOnce(String operation) {
            failNext.add(operation);
        }

        private synchronized void maybeFail(String operation) {
            if (failNext.remove(operation)) {
                throw new RuntimeException("simulated failure on " + operation);
            }
        }

        @Override
        public long logBegin(long txId) {
            maybeFail("logBegin");
            return real.logBegin(txId);
        }

        @Override
        public long logChange(long txId, DataChange change) {
            maybeFail("logChange");
            return real.logChange(txId, change);
        }

        @Override
        public long logSchemaChange(long txId, SchemaChange change) {
            maybeFail("logSchemaChange");
            return real.logSchemaChange(txId, change);
        }

        @Override
        public void logCommit(long txId) {
            maybeFail("logCommit");
            real.logCommit(txId);
        }

        @Override
        public void logAbort(long txId) {
            maybeFail("logAbort");
            real.logAbort(txId);
        }

        @Override
        public void ensureDurable(long lsn) {
            real.ensureDurable(lsn);
        }

        @Override
        public void rollback(long txId) {
            maybeFail("rollback");
            real.rollback(txId);
        }

        @Override
        public RecoveryResult recover() {
            return real.recover();
        }

        @Override
        public void checkpoint() {
            real.checkpoint();
        }

        @Override
        public void recoverTo(RecoveryTarget target) {
            real.recoverTo(target);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Full QP write: WRITE access, WAL permit, apply lease, SM write stamped with the LSN, confirm. */
    private static WritePermit write(Rig r, TxId tx, long sequence, String table, int slot, int newValue) {
        expect(acquire(r.adapter, tx, table, AccessMode.WRITE).status() == AccessStatus.GRANTED, "akses WRITE " + table);
        WritePermit permit = r.adapter.authorizeChange(change(r, tx, sequence, table, slot, newValue));
        try (WriteLease lease = r.adapter.acquireApplyLease(permit)) {
            r.sm.writeAt(table, rid(table, slot), intImage(newValue), permit.lsn().value());
            r.adapter.confirmApplied(permit.changeId());
        }
        return permit;
    }

    private static ChangeRecord change(Rig r, TxId tx, long sequence, String table, int slot, int newValue) {
        return change(r, tx, sequence, table, slot, newValue, r.value(table, slot));
    }

    private static ChangeRecord change(Rig r, TxId tx, long sequence, String table, int slot, int newValue, int oldValue) {
        return new ChangeRecord(new ChangeId(tx, sequence), tx,
                new DataChange(ChangeOperation.UPDATE, table, rid(table, slot), intImage(oldValue), intImage(newValue)));
    }

    private static SchemaChange createTable(String name) {
        TableSchema schema = new TableSchema("99", name,
                List.of(new ColumnSchema("id", DataType.INTEGER, Optional.empty())));
        return new SchemaChange(
                List.of(new SchemaImage(name, Optional.empty(), List.of(), Optional.empty())),
                List.of(new SchemaImage(name, Optional.of(schema), List.of(), Optional.empty())));
    }

    private static RID rid(String table, int slot) {
        return new RID(new PageId(table, 0L), slot);
    }

    private static RecordImage intImage(int v) {
        return new RecordImage(true, List.of(
                new Value(DataType.INTEGER, Optional.of(v), Optional.empty(), Optional.empty())));
    }

    private static ResourceKey table(String name) {
        return ResourceKey.table(new TableId(name));
    }

    private static AccessDecision acquire(ConcurrencyAdapter a, TxId tx, String table, AccessMode mode) {
        AccessDecision decision = a.acquireAccess(tx, table(table), mode);
        while (decision.status() == AccessStatus.WAIT) {
            decision = a.waitAccess(decision.requestId());
        }
        return decision;
    }

    private static boolean granted(ConcurrencyAdapter a, TxId tx, String table, AccessMode mode) {
        return acquire(a, tx, table, mode).status() == AccessStatus.GRANTED;
    }

    /** True when granted; false when the transaction was aborted (deadlock victim). */
    private static boolean grantedOrAborted(ConcurrencyAdapter a, TxId tx, String table) {
        try {
            return acquire(a, tx, table, AccessMode.WRITE).status() == AccessStatus.GRANTED;
        } catch (QpException e) {
            if (e.getErrorInfo().code() == ErrorCode.TRANSACTION_ABORTED) {
                return false;
            }
            throw e;
        }
    }

    /** Waits briefly for background aborts (deadlock victims) to finish, then requires an empty CCM. */
    private static void assertClean(Rig r) throws InterruptedException {
        String left = r.ccm.leftoverState();
        for (int i = 0; i < 40 && !left.isEmpty(); i++) {
            Thread.sleep(50);
            left = r.ccm.leftoverState();
        }
        expect(left.isEmpty(), "sisa lock atau data protokol setelah skenario: " + left);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("sqlike-ccm-qp-frm-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T get(Future<T> f, int seconds) {
        try {
            return f.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("timeout setelah " + seconds + " detik (kemungkinan hang di CCM)");
        } catch (Exception e) {
            throw new AssertionError("future gagal: " + e.getCause(), e);
        }
    }

    private static boolean isDone(Future<?> f, long millis) {
        try {
            f.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void expectThrows(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(what);
    }

    private static void run(String name, ProtocolType protocol, Scenario scenario) {
        try (Rig rig = Rig.open(tempDir(), protocol, true)) {
            scenario.run(rig);
            if (!rig.cleanChecked) {
                assertClean(rig);
            }
            passed++;
            System.out.println("[LULUS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("[GAGAL] " + name + "\n         -> " + t.getMessage());
        }
    }
}
