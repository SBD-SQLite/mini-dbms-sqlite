package sqlike.frm.recovery;

import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.RecoveryState;
import sqlike.frm.port.SmPort;
import sqlike.shared.RecoveryResult;

import java.util.List;
import java.util.Optional;

public class RecoveryManager {
    private RecoveryState state;
    private final LogManager logManager;
    private final RecoveryAnalyzer analyzer;
    private final ChangeApplier applier;
    private final SmPort sm;
    private RecoveryResult lastResult;

    public RecoveryManager(LogManager logManager, SmPort sm) {
        this.logManager = logManager;
        this.sm = sm;
        this.analyzer = new RecoveryAnalyzer();
        this.applier = new ChangeApplier(sm);
        this.state = RecoveryState.NOT_STARTED;
    }

    public synchronized RecoveryResult recover() throws Exception {
        if (state == RecoveryState.READY && lastResult != null) return lastResult;
        if (state == RecoveryState.RUNNING) throw new IllegalStateException("Recovery sedang berjalan!");

        state = RecoveryState.RUNNING;

        try {
            // baca log
            List<LogRecord> records = logManager.readLogs(null);

            // fase analysis
            RecoveryPlan plan = analyzer.analyze(records);

            // fase undo
            for (LogRecord undoRec : plan.undoChanges) {
                applier.undo(undoRec);
            }

            // fase redo
            for (LogRecord redoRec : plan.redoChanges) {
                applier.apply(redoRec);
            }

            // fase finalize
            for (Long txId : plan.needsAbortTx) {
                logManager.logAbort(txId); // return void
                logManager.ensureDurable(logManager.getLastLsn()); 
            }

            sm.flushAll();

            state = RecoveryState.READY;
            
            lastResult = new RecoveryResult(
                plan.maxTxId,
                plan.lastTimestamp,
                plan.lastRecoveredLSN,
                plan.checkpointLsn != null ? Optional.of(plan.checkpointLsn) : Optional.empty()
            );
            
            return lastResult;

        } catch (Exception e) {
            state = RecoveryState.FAILED;
            throw new Exception("Recovery gagal: " + e.getMessage(), e);
        }
    }

    public RecoveryState getState() {
        return state;
    }
}