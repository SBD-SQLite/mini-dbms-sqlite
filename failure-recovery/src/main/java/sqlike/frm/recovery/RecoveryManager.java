package sqlike.frm.recovery;

import sqlike.frm.Log.LogManager;
import sqlike.frm.Log.LogRecord;
import sqlike.frm.RecoveryState;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.frm.model.RecoveryPlan;
import sqlike.frm.model.RecoveryReport;
import java.util.List;

/**
 * FR5: Crash Recovery Engine.
 * Bertanggung jawab memulihkan basis data secara otomatis setelah sistem mati mendadak (FR-07).
 */
public class RecoveryManager {
    private RecoveryState state;
    private LogManager logManager;
    private RecoveryAnalyzer analyzer;
    private ChangeApplier applier;
    private SmPort sm;
    private CcmPort ccm;
    private RecoveryReport lastReport;

    public RecoveryManager(LogManager logManager, SmPort sm, CcmPort ccm) {
        this.logManager = logManager;
        this.sm = sm;
        this.ccm = ccm;
        this.analyzer = new RecoveryAnalyzer();
        this.applier = new ChangeApplier(sm);
        this.state = RecoveryState.NOT_STARTED;
    }

    /**
     * Titik masuk utama untuk Crash Recovery.
     */
    public RecoveryReport recover() {
        if (state == RecoveryState.READY || state == RecoveryState.RUNNING) {
            // TODO: Jika sudah recover, kembalikan lastReport agar tidak terjadi double-recovery
            return lastReport;
        }

        state = RecoveryState.RUNNING;

        try {
            // TODO: 1. Dapatkan seluruh log (atau dari checkpoint terakhir) via logManager.readLogs()
            List<LogRecord> records = null; // Ganti dengan pemanggilan logManager

            // TODO: 2. Fase ANALYSIS: Panggil analyzer.analyze(records) untuk mendapatkan RecoveryPlan
            RecoveryPlan plan = new RecoveryPlan(); 

            // TODO: 3. Fase REDO: Panggil method redoCommitted(plan)
            
            // TODO: 4. Fase UNDO: Panggil method undoUncommitted(plan)

            // TODO: 5. Fase FINALIZE: Panggil method finalizeRecovery(plan)
            // Di sini juga panggil ccm.initAfterRecovery(plan.maxTxId) agar sinkron

            state = RecoveryState.READY;
            // lastReport = ...
            return lastReport;

        } catch (Exception e) {
            // TODO: Tangani error, ubah state menjadi FAILED, panggil fail()
            return fail("RECOVERY_FAILED", e.getMessage());
        }
    }

    public RecoveryState getState() {
        return state;
    }

    /**
     * Menjalankan ulang perubahan dari transaksi yang sudah COMMIT.
     */
    private int redoCommitted(RecoveryPlan plan) {
        // TODO: Iterasi plan.redoChanges, panggil applier.apply(change, lsn)
        throw new UnsupportedOperationException("TODO: Implementasi redoCommitted()");
    }

    /**
     * Membatalkan perubahan dari transaksi yang belum COMMIT.
     */
    private int undoUncommitted(RecoveryPlan plan) {
        // TODO: Iterasi plan.undoChanges secara mundur (reverse), panggil applier.undo(change, lsn)
        throw new UnsupportedOperationException("TODO: Implementasi undoUncommitted()");
    }

    /**
     * Menyimpan hasil pemulihan dan menyinkronkan state ke CCM/SM.
     */
    private int finalizeRecovery(RecoveryPlan plan) {
        // TODO: Pastikan SM melakukan flush_all()
        // TODO: Tulis log ABORT untuk transaksi yang ada di plan.needsAbortTx
        // TODO: Panggil ccm.init_after_recovery(plan.maxTxId)
        throw new UnsupportedOperationException("TODO: Implementasi finalizeRecovery()");
    }

    /**
     * Mengembalikan laporan kegagalan jika pemulihan tidak bisa diselesaikan.
     */
    private RecoveryReport fail(String code, String msg) {
        state = RecoveryState.FAILED;
        RecoveryReport report = new RecoveryReport();
        report.success = false;
        report.errorCode = code;
        report.errorMessage = msg;
        this.lastReport = report;
        return report;
    }
}