package sqlike.frm.recovery;

import sqlike.frm.Log.LogRecord;
import sqlike.frm.model.RecoveryPlan;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class RecoveryAnalyzer {
    
    public RecoveryPlan analyze(List<LogRecord> records) {
        RecoveryPlan plan = new RecoveryPlan();
        plan.startLsn = 0;
        plan.checkpointLsn = null;
        plan.committedTx = new HashSet<>();
        plan.uncommittedTx = new HashSet<>();
        plan.needsAbortTx = new HashSet<>();
        plan.undoChanges = Collections.emptyList();
        plan.redoChanges = Collections.emptyList();
        plan.maxTxId = 0;
        
        // TODO: Implementasi findLastCheckpoint() untuk mencari CHECKPOINT terakhir
        // TODO: Implementasi classify() untuk menentukan status transaksi
        // CATATAN: Transaksi yang memiliki ABORT tapi belum sampai ke disk juga harus masuk ke uncommittedTx (perlu di-undo)
        // CATATAN: txOutcome dihitung dari SELURUH log, sedangkan redo/undoChanges hanya dihitung dari startLsn
        
        return plan;
    }
}