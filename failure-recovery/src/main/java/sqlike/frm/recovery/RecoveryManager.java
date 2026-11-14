package sqlike.frm.recovery;

import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.RecoveryState;
import sqlike.frm.port.SmPort;
import sqlike.shared.RecoveryResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            // 1. Baca log
            List<LogRecord> records = logManager.readLogs(null);

            // 2. Fase Analysis
            RecoveryPlan plan = analyzer.analyze(records);

            // 3. Fase Undo
            // Kumpulkan semua CLR yang sudah ada (jika recovery sebelumnya terputus)
            Map<Long, LogRecord> clrByOriginalLsn = new HashMap<>();
            for (LogRecord record : records) {
                if (record.undoOfLSN().isPresent()) {
                    clrByOriginalLsn.put(record.undoOfLSN().get(), record);
                }
            }

            for (LogRecord undoRec : plan.undoChanges) {
                long txId = undoRec.txID().orElseThrow();
                LogRecord clr = clrByOriginalLsn.get(undoRec.lsn());

                // Jika belum ada CLR, dan transaksi ini memang butuh di-abort, buat CLR baru
                if (clr == null && plan.needsAbortTx.contains(txId)) {
                    clr = logManager.logCompensation(txId, undoRec.lsn());
                    logManager.ensureDurable(clr.lsn()); // Pastikan CLR aman di disk
                    clrByOriginalLsn.put(undoRec.lsn(), clr);
                }

                // Gunakan LSN dari CLR (jika ada), jika tidak ada baru pakai LSN asli
                long lsnToUse = clr != null ? clr.lsn() : undoRec.lsn();
                applier.undo(undoRec, lsnToUse);
            }

            // 4. Fase Redo
            for (LogRecord redoRec : plan.redoChanges) {
                applier.apply(redoRec);
            }

            // 5. Fase Finalize
            for (Long txId : plan.needsAbortTx) {
                logManager.logAbort(txId);
                logManager.ensureDurable(logManager.getLastLsn()); 
            }

            sm.flushAll();

            state = RecoveryState.READY;
            
            lastResult = new RecoveryResult(
                Math.max(plan.maxTxId, logManager.getMaxTxId()),
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