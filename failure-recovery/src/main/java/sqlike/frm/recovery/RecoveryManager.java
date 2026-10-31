package sqlike.frm.recovery;

import sqlike.frm.Log.LogManager;
import sqlike.frm.RecoveryError;
import sqlike.frm.RecoveryState;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.frm.model.RecoveryPlan;
import sqlike.frm.model.RecoveryReport;
import java.util.Collections;

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

    public RecoveryReport recover() {
        // Mengembalikan hasil bila recovery sudah selesai
        // TODO: Tambahkan mekanisme sinkronisasi (misal: synchronized/locking) untuk mencegah race condition jika dipanggil bersamaan saat state RUNNING
        if (lastReport != null && (state == RecoveryState.READY || state == RecoveryState.RUNNING)) {
            return lastReport;
        }

        state = RecoveryState.RUNNING;

        try {
            RecoveryPlan plan = new RecoveryPlan();
            plan.maxTxId = 0; 

            // TODO: 1. Dapatkan log via logManager.readLogs()
            // TODO: Jika ada log terpotong (truncated) di ekor, POTONG/HAPUS bagian tersebut dari file log sebelum proses lanjut
            // TODO: Tangani korupsi tengah log dengan melempar RecoveryError.LOG_INCONSISTENT
            // TODO: 2. plan = analyzer.analyze(records)

            // TODO: 3. Lakukan Fase UNDO terlebih dahulu
            // undoUncommitted(plan);

            // TODO: 4. Lakukan Fase REDO
            // redoCommitted(plan);

            // TODO: 5. finalizeRecovery(plan)
            
            lastReport = new RecoveryReport();
            lastReport.success = true;
            lastReport.maxTxId = plan.maxTxId;
            // Kontrak: txOutcome bersifat read-only (tidak boleh dimodifikasi pemanggil)
            lastReport.txOutcome = Collections.emptyMap(); 
            
            state = RecoveryState.READY;
            return lastReport;

        } catch (Exception e) {
            // TODO: Petakan exception ke RecoveryError yang spesifik per fase
            return fail(RecoveryError.LOG_READ_FAILED, e.getMessage());
        }
    }

    public RecoveryState getState() {
        return state;
    }

    private int redoCommitted(RecoveryPlan plan) {
        // TODO: Iterasi plan.redoChanges, panggil applier.apply()
        return 0;
    }

    private int undoUncommitted(RecoveryPlan plan) {
        // TODO: Iterasi plan.undoChanges secara mundur, panggil applier.undo()
        return 0;
    }

        private int finalizeRecovery(RecoveryPlan plan) {
        // TODO: 1. Panggil sm.flush_all() agar semua data aman di disk
        // TODO: 2. Tulis log ABORT untuk transaksi di plan.needsAbortTx (setelah undo aman)
        // TODO: 3. Pastikan log ABORT tersebut sudah persisten (flush log / ensure_durable)
        // TODO: 4. Panggil ccm.initAfterRecovery(plan.maxTxId) PALING AKHIR
        return 0;
    }

    private RecoveryReport fail(RecoveryError code, String msg) {
        state = RecoveryState.FAILED;
        RecoveryReport report = new RecoveryReport();
        report.success = false;
        report.errorCode = code;
        report.errorMessage = msg;
        this.lastReport = report;
        return report;
    }
}