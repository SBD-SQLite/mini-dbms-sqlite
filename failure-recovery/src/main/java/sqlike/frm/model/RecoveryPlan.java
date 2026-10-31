package sqlike.frm.model;

import sqlike.frm.Log.LogRecord;
import java.util.List;
import java.util.Set;

// Menyimpan hasil analisis log (Fase Analysis) untuk dieksekusi pada Fase Redo/Undo
public class RecoveryPlan {
    public int startLsn;
    public int checkpointLsn;
    public Set<Integer> committedTx;
    public Set<Integer> uncommittedTx;
    public Set<Integer> needsAbortTx;
    public List<LogRecord> undoChanges;
    public List<LogRecord> redoChanges;
    public int maxTxId;
    
    // TODO: Tambahkan struktur data txOutcome jika diperlukan (Map<Integer, TxOutcome>)
}