package sqlike.frm.model;

import sqlike.frm.Log.LogRecord;
import sqlike.frm.TxOutcome;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RecoveryPlan {
    public int startLsn;
    public Integer checkpointLsn;
    public Set<Integer> committedTx;
    public Set<Integer> uncommittedTx;
    public Set<Integer> needsAbortTx;
    public List<LogRecord> undoChanges;
    public List<LogRecord> redoChanges;
    public int maxTxId;
    public Map<Integer, TxOutcome> txOutcome; 
}