package sqlike.frm.model;

import sqlike.frm.RecoveryError;
import sqlike.frm.TxOutcome;
import java.util.Map;

public class RecoveryReport {
    public boolean success;
    public RecoveryError errorCode; 
    public String errorMessage;
    public Integer checkpointLsn;   
    public int committedCount;
    public int undoCount;
    public int redoCount;
    public int lastLsn;
    public int maxTxId;
    public Map<Integer, TxOutcome> txOutcome; 
}