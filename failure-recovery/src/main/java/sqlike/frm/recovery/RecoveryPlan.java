package sqlike.frm.recovery;

import sqlike.frm.log.LogRecord;
import java.util.List;
import java.util.Set;

public class RecoveryPlan {
    public long startLsn;
    public Long checkpointLsn;
    public Set<Long> committedTx;
    public Set<Long> uncommittedTx;
    public Set<Long> abortedTx;
    public Set<Long> needsAbortTx;
    public List<LogRecord> undoChanges;
    public List<LogRecord> redoChanges;
    public long maxTxId;
    public long lastTimestamp;
    public long lastRecoveredLSN;
}