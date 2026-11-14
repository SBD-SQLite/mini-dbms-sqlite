package sqlike.frm.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Sesuaikan nama package/kelas SM & CCM asli dari kelompok lain
import sqlike.ccm.ConcurrencyControlManager;
import sqlike.frm.FailureRecoveryManager;
import sqlike.frm.FrmConfig;
import sqlike.frm.FrmException;
import sqlike.frm.adapter.ConcurrencyControlAdapter;
import sqlike.frm.adapter.StorageManagerAdapter;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.sm.StorageManager;

class RollbackE2EIntegrationTests {

    @TempDir
    Path tempDir;

    private StorageManager realSm;
    private ConcurrencyControlManager realCcm;
    private FailureRecoveryManager frm;

    @BeforeEach
    void setUp() {
        Path smDataDir = tempDir.resolve("sm_data");
        Path walPath = tempDir.resolve("frm_wal.log");

        // 1. Inisialisasi SM dan CCM asli beserta adapternya
        realSm = new StorageManager(smDataDir);
        realCcm = new ConcurrencyControlManager();

        StorageManagerAdapter smAdapter = new StorageManagerAdapter(realSm);
        ConcurrencyControlAdapter ccmAdapter = new ConcurrencyControlAdapter(realCcm);

        FrmConfig config = new FrmConfig(walPath.toString(), 60_000L, 30_000L);
        frm = new FailureRecoveryManager(smAdapter, ccmAdapter, config);

        // Jalankan startup recovery agar status FRM menjadi READY
        frm.recover();
    }

    /**
     * Skenario 1: Verifikasi integrasi SM asli (DML + DDL rollback & buffer/disk durability).
     */
    @Test
    void e2eRollback_revertsDmlAndDdlOnRealStorageManager() {
        long txId = realCcm.beginTransaction();
        frm.logBegin(txId);

        // Buat tabel via SM asli & catat log DDL
        TableSchema schema = new TableSchema("produk", List.of(
                new TableSchema.ColumnDef("id", DataType.INTEGER, true),
                new TableSchema.ColumnDef("nama", DataType.VARCHAR, false)
        ));
        realSm.createTable(schema);
        SchemaImage beforeDdl = new SchemaImage("produk", Optional.empty(), List.of(), Optional.empty());
        SchemaImage afterDdl = new SchemaImage("produk", Optional.of(schema), List.of(), Optional.empty());
        frm.logSchemaChange(txId, new SchemaChange(List.of(beforeDdl), List.of(afterDdl)));

        // Insert record via SM asli & catat log DML
        RecordImage emptyRec = new RecordImage(false, List.of());
        RecordImage newRec = new RecordImage(true, List.of(10, "Laptop"));
        RID rid = realSm.insertRecord("produk", newRec);
        frm.logChange(txId, new DataChange(ChangeOperation.INSERT, "produk", rid, emptyRec, newRec));

        // Picu abort/rollback melalui CCM & FRM
        realCcm.abortTransaction(txId); // CCM memanggil frm.rollback(txId) dan frm.logAbort(txId)

        // Pastikan record hilang di SM asli & skema tabel ikut terhapus dari katalog SM asli
        assertFalse(realSm.tableExists("produk"), "Tabel produk harus terhapus di SM asli pasca rollback");
        List<LogRecord> logs = frm.getLogsByTx(txId);
        assertEquals(LogType.ABORT, logs.get(logs.size() - 1).type());
    }

    /**
     * Skenario 2: Verifikasi isolasi transaksi & pelepasan lock dengan CCM asli.
     * T1 melakukan update lalu di-rollback, sementara T2 mengakses item berbeda/menunggu lock T1.
     */
    @Test
    void e2eConcurrentRollback_releasesLocksAndPreservesCommittedPeerTx() throws Exception {
        // Setup awal tabel & 2 baris data yang sudah committed
        long txInit = realCcm.beginTransaction();
        frm.logBegin(txInit);
        TableSchema schema = new TableSchema("akun", List.of(
                new TableSchema.ColumnDef("id", DataType.INTEGER, true),
                new TableSchema.ColumnDef("saldo", DataType.INTEGER, false)
        ));
        realSm.createTable(schema);
        RID rid1 = realSm.insertRecord("akun", new RecordImage(true, List.of(1, 500)));
        RID rid2 = realSm.insertRecord("akun", new RecordImage(true, List.of(2, 1000)));
        frm.logCommit(txInit);
        realCcm.commitTransaction(txInit);

        // T1 mengubah rid1 (akan di-rollback), T2 mengubah rid2 (akan di-commit)
        long tx1 = realCcm.beginTransaction();
        long tx2 = realCcm.beginTransaction();
        frm.logBegin(tx1);
        frm.logBegin(tx2);

        RecordImage old1 = new RecordImage(true, List.of(1, 500));
        RecordImage mod1 = new RecordImage(true, List.of(1, 100));
        realCcm.acquireExclusiveLock(tx1, "akun", rid1);
        realSm.updateRecord("akun", rid1, mod1);
        frm.logChange(tx1, new DataChange(ChangeOperation.UPDATE, "akun", rid1, old1, mod1));

        RecordImage old2 = new RecordImage(true, List.of(2, 1000));
        RecordImage mod2 = new RecordImage(true, List.of(2, 1500));
        realCcm.acquireExclusiveLock(tx2, "akun", rid2);
        realSm.updateRecord("akun", rid2, mod2);
        frm.logChange(tx2, new DataChange(ChangeOperation.UPDATE, "akun", rid2, old2, mod2));

        // Commit T2 dan Rollback T1
        frm.logCommit(tx2);
        realCcm.commitTransaction(tx2);

        realCcm.abortTransaction(tx1);

        // Verifikasi: rid1 kembali ke 500, rid2 tetap 1500, dan lock rid1 sudah dilepas CCM
        assertEquals(List.of(1, 500), realSm.readRecord("akun", rid1).values());
        assertEquals(List.of(2, 1500), realSm.readRecord("akun", rid2).values());

        long tx3 = realCcm.beginTransaction();
        assertDoesNotThrow(() -> realCcm.acquireExclusiveLock(tx3, "akun", rid1),
                "Lock pada rid1 harus sudah dilepas oleh CCM setelah rollback T1 selesai");
        realCcm.abortTransaction(tx3);
    }

    /**
     * Skenario 3: Verifikasi kasus batas (UNKNOWN_TX & ALREADY_COMMITTED) pada sistem terintegrasi.
     */
    @Test
    void e2eRollbackBoundaryCases_throwsExpectedFrmException() {
        // 1. Transaksi tidak dikenal
        FrmException notFoundEx = assertThrows(FrmException.class, () -> frm.rollback(99999L));
        assertEquals(ErrorCode.NOT_FOUND, notFoundEx.getErrorCode());

        // 2. Transaksi sudah committed
        long txCommitted = realCcm.beginTransaction();
        frm.logBegin(txCommitted);
        frm.logCommit(txCommitted);
        realCcm.commitTransaction(txCommitted);

        FrmException invalidStateEx = assertThrows(FrmException.class, () -> frm.rollback(txCommitted));
        assertEquals(ErrorCode.INVALID_STATE, invalidStateEx.getErrorCode());
    }
}