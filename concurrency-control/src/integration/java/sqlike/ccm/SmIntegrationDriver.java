package sqlike.ccm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.LogManager;

import sqlike.block.BlockManager;
import sqlike.buffer.BufferPoolManager;
import sqlike.catalog.CatalogManager;
import sqlike.frm.FailureRecoveryManager;
import sqlike.frm.FrmConfig;
import sqlike.frm.adapter.ConcurrencyControlAdapter;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.index.IndexManager;
import sqlike.record.RecordManager;
import sqlike.record.RecordSerializer;
import sqlike.replacement_durability.LRUReplacementPolicy;
import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ChangeOperation;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.InsertReservation;
import sqlike.shared.ProtocolType;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryResult;
import sqlike.shared.ResourceId;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.shared.TransactionStatus;
import sqlike.shared.Value;
import sqlike.statistics.StatisticsManager;
import sqlike.storage.StorageManager;

/**
 * Integration driver with real data from the Storage Manager: the real SM (pages, buffer, RIDs),
 * the real FRM (WAL, rollback, recovery) and the real CCM. No component is mocked.
 *
 * <p>Every RID comes from {@code StorageManager.reserveInsert}. Each write follows the two-stage
 * contract: requestWriteAccess on the RECORD resource with that RID, requestWrite (WAL), beginApply,
 * SM write stamped with the LSN, endApply. Per PRD section 9, CCM locks the record's table, so two
 * transactions on different RIDs of one table still wait for each other.
 *
 * <p>FRM reaches the SM through {@link RealSmPort}, which CCM owns: FRM's own StorageManagerAdapter
 * calls createSnapshot/restoreSnapshot, which the real SM does not have yet, and FRM.recover() takes a
 * baseline snapshot on first startup, so RealSmPort copies the SM data files itself. Tables are created
 * directly on the SM during setup, outside any transaction. Every scenario runs on a fresh data directory and must leave no
 * lock or protocol data behind (ConcurrencyControlManager.leftoverState).
 */
public final class SmIntegrationDriver {

    private static final String TABLE = "akun";
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

    public static void main(String[] args) {
        LogManager.getLogManager().reset();

        for (ProtocolType protocol : ProtocolType.values()) {
            String p = "[" + protocol + "] ";
            run(p + "SM-01 INSERT lewat SM asli: RID dari reserveInsert, lock RECORD, WAL, commit", protocol, SmIntegrationDriver::insertCommit);
            run(p + "SM-02 UPDATE pada RID asli lalu ABORT: FRM mengundo di SM asli", protocol, SmIntegrationDriver::updateAbort);
            run(p + "SM-03 DELETE pada RID asli lalu ABORT: record kembali di RID yang sama", protocol, SmIntegrationDriver::deleteAbort);
            run(p + "SM-04 INSERT lalu ABORT: RID asli kosong lagi", protocol, SmIntegrationDriver::insertAbort);
            run(p + "SM-05 Dua transaksi pada RID berbeda di satu tabel: penulis kedua menunggu (eskalasi ke tabel)", protocol, SmIntegrationDriver::differentRidsSameTable);
            run(p + "SM-06 Banyak INSERT: RID asli unik di beberapa page", protocol, SmIntegrationDriver::manyInserts);
            run(p + "SM-08 CREATE TABLE lalu ABORT: FRM memanggil restoreSchema di SM asli, tabel hilang", protocol, SmIntegrationDriver::createTableAbort);
            run(p + "SM-07 Restart: SM dibuka ulang, FRM.recover, initAfterRecovery; redo commit, undo aktif", protocol, SmIntegrationDriver::restartRecovery);
        }

        System.out.println();
        System.out.println("Total: " + passed + "/" + (passed + failed) + " skenario integrasi SM LULUS");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- scenarios

    private static void insertCommit(Rig r) {
        long tx = r.begin();
        RID rid = r.insert(tx, 42);
        expect(rid.pageId().pageNumber() >= 0 && rid.slotId() >= 0, "RID dari SM valid: " + rid);
        expect(r.commit(tx) == TransactionStatus.COMMITTED, "commit COMMITTED");
        expect(r.value(rid).equals(Optional.of(42)), "SM asli berisi 42 di " + rid + ", dapat " + r.value(rid));
        expect(r.logTypes(tx).equals(List.of(LogType.BEGIN, LogType.INSERT, LogType.COMMIT)),
                "urutan WAL BEGIN, INSERT, COMMIT, dapat " + r.logTypes(tx));
        expect(r.loggedRids(tx).equals(List.of(rid)), "RID di log FRM sama dengan RID dari SM: " + r.loggedRids(tx));
    }

    private static void updateAbort(Rig r) {
        RID rid = r.seed(100);
        long tx = r.begin();
        r.update(tx, rid, 999);
        expect(r.value(rid).equals(Optional.of(999)), "SM berisi nilai baru sebelum abort");
        expect(r.abort(tx) == TransactionStatus.ABORTED, "abort ABORTED");
        expect(r.value(rid).equals(Optional.of(100)), "undo FRM mengembalikan 100 di " + rid + ", dapat " + r.value(rid));
        expect(r.logTypes(tx).equals(List.of(LogType.BEGIN, LogType.UPDATE, LogType.COMPENSATION, LogType.ABORT)),
                "urutan WAL BEGIN, UPDATE, COMPENSATION, ABORT, dapat " + r.logTypes(tx));
    }

    private static void deleteAbort(Rig r) {
        RID rid = r.seed(7);
        long tx = r.begin();
        r.delete(tx, rid);
        expect(r.value(rid).isEmpty(), "record terhapus di SM sebelum abort");
        expect(r.abort(tx) == TransactionStatus.ABORTED, "abort ABORTED");
        expect(r.value(rid).equals(Optional.of(7)), "record kembali di RID yang sama " + rid + ", dapat " + r.value(rid));
    }

    private static void insertAbort(Rig r) {
        long tx = r.begin();
        RID rid = r.insert(tx, 5);
        expect(r.value(rid).equals(Optional.of(5)), "record ada sebelum abort");
        expect(r.abort(tx) == TransactionStatus.ABORTED, "abort ABORTED");
        expect(r.value(rid).isEmpty(), "RID " + rid + " kosong lagi setelah undo, dapat " + r.value(rid));
    }

    private static void createTableAbort(Rig r) {
        long tx = r.begin();
        String name = "tabel_baru";
        TableSchema schema = new TableSchema("77", name, List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty())));
        SchemaChange change = new SchemaChange(
                List.of(new SchemaImage(name, Optional.empty(), List.of(), Optional.empty())),
                List.of(new SchemaImage(name, Optional.of(schema), List.of(), Optional.empty())));
        AccessResponse write = untilDecided(() -> r.ccm.requestSchemaWrite(tx, change));
        expect(write.status() == AccessStatus.GRANTED && write.changeLSN().isPresent(), "requestSchemaWrite GRANTED, dapat " + write);
        long lsn = write.changeLSN().get();
        expect(r.ccm.beginApply(tx, lsn), "beginApply diizinkan untuk LSN " + lsn);
        try {
            r.sm.createTable(schema, lsn);
        } finally {
            r.ccm.endApply(tx, lsn);
        }
        expect(r.sm.listTables().contains(name), "tabel ada sebelum abort");
        expect(r.abort(tx) == TransactionStatus.ABORTED, "abort DDL ABORTED");
        expect(!r.sm.listTables().contains(name), "tabel hilang setelah restoreSchema");
    }

    private static void differentRidsSameTable(Rig r) {
        RID first = r.seed(1);
        RID second = r.seed(2);
        expect(!first.equals(second), "dua RID asli berbeda: " + first + " dan " + second);
        long t1 = r.begin();
        long t2 = r.begin();
        r.update(t1, first, 10);
        Future<?> waiting = POOL.submit(() -> r.update(t2, second, 20));
        expect(!isDone(waiting, 300), "T2 menunggu walaupun RID berbeda (lock dieskalasi ke tabel, PRD bagian 9)");
        expect(r.commit(t1) == TransactionStatus.COMMITTED, "T1 commit");
        get(waiting, 5);
        expect(r.commit(t2) == TransactionStatus.COMMITTED, "T2 commit setelah T1 melepas lock");
        expect(r.value(first).equals(Optional.of(10)) && r.value(second).equals(Optional.of(20)),
                "kedua nilai tersimpan di SM asli");
    }

    private static void manyInserts(Rig r) {
        long tx = r.begin();
        Set<RID> rids = new HashSet<>();
        Set<Long> pages = new HashSet<>();
        int count = 1000; // a 4 KB SM page holds about 313 one-integer records
        for (int i = 0; i < count; i++) {
            RID rid = r.insert(tx, i);
            rids.add(rid);
            pages.add(rid.pageId().pageNumber());
        }
        expect(r.commit(tx) == TransactionStatus.COMMITTED, "commit COMMITTED");
        expect(rids.size() == count, "semua " + count + " RID unik, dapat " + rids.size());
        expect(pages.size() > 1, "RID tersebar di lebih dari satu page, dapat " + pages.size() + " page");
        expect(r.loggedRids(tx).size() == count, "satu catatan WAL per INSERT");
        System.out.println("        " + count + " RID asli di " + pages.size() + " page");
    }

    private static void restartRecovery(Rig r) {
        RID committed = r.seed(1);
        RID uncommitted = r.seed(2);
        long t1 = r.begin();
        r.update(t1, committed, 11);
        expect(r.commit(t1) == TransactionStatus.COMMITTED, "T1 commit");
        long t2 = r.begin();
        r.update(t2, uncommitted, 22);
        // Crash: nothing is flushed or closed; the SM, FRM and CCM are rebuilt from the same directory.
        r.crashAndRestart();
        expect(r.value(committed).equals(Optional.of(11)), "redo: nilai T1 yang commit ada, dapat " + r.value(committed));
        expect(r.value(uncommitted).equals(Optional.of(2)), "undo: nilai T2 yang aktif dibatalkan, dapat " + r.value(uncommitted));
        long t3 = r.begin();
        expect(t3 > t2, "txId setelah restart (" + t3 + ") lebih besar dari txId di log (" + t2 + ")");
        expect(r.commit(t3) == TransactionStatus.COMMITTED, "transaksi baru setelah restart bisa commit");
    }

    // ---------------------------------------------------------------- rig

    /** Real SM, real FRM, real CCM on one data directory. */
    private static final class Rig implements AutoCloseable {
        final Path dir;
        final ProtocolType protocol;
        StorageManager sm;
        BlockManager blocks;
        FailureRecoveryManager frm;
        ConcurrencyControlManager ccm;
        RecoveryResult recovery;

        Rig(Path dir, ProtocolType protocol) {
            this.dir = dir;
            this.protocol = protocol;
            start(true);
        }

        private void start(boolean createTable) {
            Path smDir = dir.resolve("sm");
            try {
                Files.createDirectories(smDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            WalHolder wal = new WalHolder();
            CatalogManager catalog = new CatalogManager(smDir.resolve("catalog.bin"));
            catalog.load();
            blocks = new BlockManager(smDir);
            BufferPoolManager buffer = new BufferPoolManager(blocks, wal::ensureDurable, new LRUReplacementPolicy(), 8);
            RecordSerializer serializer = new RecordSerializer();
            RecordManager records = new RecordManager(buffer, serializer, catalog);
            long[] buildLsn = {0L};
            IndexManager indexes = new IndexManager(catalog, records, buffer, serializer, () -> ++buildLsn[0]);
            sm = new StorageManager(records, catalog, new StatisticsManager(catalog, blocks), indexes, buffer);
            indexes.loadIndexes();
            if (createTable) {
                sm.createTable(new TableSchema(null, TABLE, List.of(
                        new ColumnSchema("saldo", DataType.INTEGER, Optional.empty()))));
            }

            LateCcmPort late = new LateCcmPort();
            frm = new FailureRecoveryManager(new RealSmPort(sm, smDir, dir.resolve("snapshots")), late,
                    new FrmConfig(dir.resolve("wal.log"), Duration.ZERO, Duration.ofSeconds(1)));
            wal.frm = frm;
            ccm = new ConcurrencyControlManager(frm, new CCMConfig(protocol, 5000L));
            late.delegate = new ConcurrencyControlAdapter(ccm);
            // PRD section 14 startup order: FRM.recover, then CCM.initAfterRecovery(lastTxId).
            recovery = frm.recover();
            ccm.initAfterRecovery(recovery.lastTxId());
        }

        void crashAndRestart() {
            frm.close();
            blocks.close();
            start(false);
        }

        long begin() {
            var response = ccm.beginTransaction();
            expect(response.status() == TransactionStatus.ACTIVE, "begin ACTIVE, dapat " + response);
            return response.txId();
        }

        TransactionStatus commit(long tx) {
            return ccm.commit(tx).status();
        }

        TransactionStatus abort(long tx) {
            return ccm.abort(tx).status();
        }

        /** Inserts and commits a row outside the scenario's transactions; returns its real RID. */
        RID seed(int saldo) {
            long tx = begin();
            RID rid = insert(tx, saldo);
            expect(commit(tx) == TransactionStatus.COMMITTED, "seed commit");
            return rid;
        }

        RID insert(long tx, int saldo) {
            InsertReservation reservation = sm.reserveInsert(TABLE, List.of(integer(saldo)));
            RID rid = reservation.rid();
            long lsn = authorize(tx, ChangeOperation.INSERT, rid, absent(), image(saldo));
            RID stored = apply(tx, lsn, () -> sm.insert(reservation, lsn));
            expect(stored.equals(rid), "SM menyimpan di RID hasil reservasi " + rid + ", dapat " + stored);
            return rid;
        }

        void update(long tx, RID rid, int saldo) {
            RecordImage before = current(rid);
            long lsn = authorize(tx, ChangeOperation.UPDATE, rid, before, image(saldo));
            apply(tx, lsn, () -> {
                sm.update(TABLE, rid, List.of(integer(saldo)), lsn);
                return null;
            });
        }

        void delete(long tx, RID rid) {
            RecordImage before = current(rid);
            long lsn = authorize(tx, ChangeOperation.DELETE, rid, before, absent());
            apply(tx, lsn, () -> {
                sm.delete(TABLE, rid, lsn);
                return null;
            });
        }

        /** Two-stage write: lock the RECORD resource, then log the change and get its LSN. */
        private long authorize(long tx, ChangeOperation op, RID rid, RecordImage before, RecordImage after) {
            ResourceId resource = Resources.record(TABLE, rid);
            AccessResponse access = untilDecided(() -> ccm.requestWriteAccess(tx, resource));
            expect(access.status() == AccessStatus.GRANTED, "requestWriteAccess " + rid + " GRANTED, dapat " + access);
            AccessResponse write = untilDecided(() -> ccm.requestWrite(tx, new DataChange(op, TABLE, rid, before, after)));
            expect(write.status() == AccessStatus.GRANTED && write.changeLSN().isPresent(),
                    "requestWrite " + op + " " + rid + " memberi LSN, dapat " + write);
            return write.changeLSN().get();
        }

        private <T> T apply(long tx, long lsn, java.util.function.Supplier<T> smWrite) {
            expect(ccm.beginApply(tx, lsn), "beginApply diizinkan untuk LSN " + lsn);
            try {
                return smWrite.get();
            } finally {
                ccm.endApply(tx, lsn);
            }
        }

        private RecordImage current(RID rid) {
            Record record = sm.fetch(TABLE, rid).orElseThrow(() -> new AssertionError("record tidak ada di " + rid));
            return new RecordImage(true, List.copyOf(record.values()));
        }

        Optional<Integer> value(RID rid) {
            return sm.fetch(TABLE, rid).flatMap(record -> record.values().get(0).integerValue());
        }

        List<LogType> logTypes(long tx) {
            List<LogType> types = new ArrayList<>();
            for (LogRecord record : frm.readLogs(Optional.of(tx))) {
                types.add(record.type());
            }
            return types;
        }

        List<RID> loggedRids(long tx) {
            List<RID> rids = new ArrayList<>();
            for (LogRecord record : frm.readLogs(Optional.of(tx))) {
                record.change().ifPresent(change -> rids.add(change.rid()));
            }
            return rids;
        }

        @Override
        public void close() {
            frm.close();
            blocks.close();
        }
    }

    /**
     * FRM's SmPort over the real SM, owned by CCM for this driver. The real SM has no snapshot API
     * yet, so createSnapshot copies the flushed SM data files itself (FRM takes one baseline snapshot
     * on first startup). restoreSnapshot is only used by PITR, which these scenarios do not run.
     */
    private static final class RealSmPort implements SmPort {
        private final StorageManager sm;
        private final Path smDir;
        private final Path snapshotsDir;

        RealSmPort(StorageManager sm, Path smDir, Path snapshotsDir) {
            this.sm = sm;
            this.smDir = smDir;
            this.snapshotsDir = snapshotsDir;
        }

        @Override
        public RecordImage readAt(String table, RID rid) {
            return sm.fetch(table, rid)
                    .map(record -> new RecordImage(true, List.copyOf(record.values())))
                    .orElseGet(SmIntegrationDriver::absent);
        }

        @Override
        public void writeAt(String table, RID rid, RecordImage image, long lsn) {
            sm.restoreRecord(table, rid, image, lsn);
        }

        @Override
        public void restoreSchema(SchemaImage image, long lsn) {
            sm.restoreSchema(image, lsn);
        }

        @Override
        public void flushAll() {
            sm.flushAll();
        }

        @Override
        public String createSnapshot() {
            sm.flushAll();
            try {
                Files.createDirectories(snapshotsDir);
                int number = 1;
                Path target;
                do {
                    target = snapshotsDir.resolve(String.format("snap-%04d", number++));
                } while (Files.exists(target));
                Files.createDirectories(target);
                try (var files = Files.list(smDir)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(file)) {
                            Files.copy(file, target.resolve(file.getFileName()));
                        }
                    }
                }
                return target.getFileName().toString();
            } catch (IOException e) {
                throw new UncheckedIOException("createSnapshot failed", e);
            }
        }

        @Override
        public void restoreSnapshot(String snapshotId) {
            throw new UnsupportedOperationException("the real SM has no restoreSnapshot yet");
        }
    }

    /** The SM buffer needs the WAL before FRM exists; FRM is plugged in once it is built. */
    private static final class WalHolder {
        volatile FailureRecoveryManager frm;

        void ensureDurable(long lsn) {
            if (frm != null) {
                frm.ensureDurable(lsn);
            }
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

    // ---------------------------------------------------------------- helpers

    /** CCM answers WAIT while a conflicting lock is held; the caller asks again, as QP does. */
    private static AccessResponse untilDecided(java.util.function.Supplier<AccessResponse> request) {
        AccessResponse response = request.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (response.status() == AccessStatus.WAIT) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("permintaan akses masih WAIT setelah 10 detik");
            }
            sleep(5);
            response = request.get();
        }
        return response;
    }

    private static Value integer(int v) {
        return new Value(DataType.INTEGER, Optional.of(v), Optional.empty(), Optional.empty());
    }

    private static RecordImage image(int v) {
        return new RecordImage(true, List.of(integer(v)));
    }

    private static RecordImage absent() {
        return new RecordImage(false, List.of());
    }

    private static void assertClean(Rig r) {
        String left = r.ccm.leftoverState();
        for (int i = 0; i < 40 && !left.isEmpty(); i++) {
            sleep(50);
            left = r.ccm.leftoverState();
        }
        expect(left.isEmpty(), "sisa lock atau data protokol setelah skenario: " + left);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("sqlike-ccm-sm-frm-");
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void run(String name, ProtocolType protocol, Scenario scenario) {
        try (Rig rig = new Rig(tempDir(), protocol)) {
            scenario.run(rig);
            assertClean(rig);
            passed++;
            System.out.println("[LULUS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("[GAGAL] " + name);
            System.out.println("        " + t);
        }
    }
}
