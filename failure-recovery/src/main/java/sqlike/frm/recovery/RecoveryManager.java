package sqlike.frm.recovery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import sqlike.frm.RecoveryError;
import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.port.SmPort;
import sqlike.shared.RecoveryResult;

public class RecoveryManager {
    private final LogManager logManager;
    private final RecoveryAnalyzer analyzer;
    private final ChangeApplier applier;
    private final SmPort sm;

    public RecoveryManager(LogManager logManager, SmPort sm) {
        this.logManager = logManager;
        this.sm = sm;
        this.analyzer = new RecoveryAnalyzer();
        this.applier = new ChangeApplier(sm);
    }

    public synchronized RecoveryResult recover() {
        List<LogRecord> records = inPhase(
                RecoveryError.LOG_READ_FAILED,
                "Unable to read the active WAL",
                () -> logManager.readLogs(null));

        RecoveryPlan plan = inPhase(
                RecoveryError.LOG_INCONSISTENT,
                "WAL analysis failed",
                () -> analyzer.analyze(records));

        inPhase(RecoveryError.UNDO_FAILED, "Undo phase failed", () -> {
            Map<Long, LogRecord> clrByOriginalLsn = new HashMap<>();
            for (LogRecord record : records) {
                if (record.undoOfLSN().isPresent()) {
                    clrByOriginalLsn.put(record.undoOfLSN().orElseThrow(), record);
                }
            }

            for (LogRecord undoRec : plan.undoChanges) {
                long txId = undoRec.txID().orElseThrow();
                LogRecord clr = clrByOriginalLsn.get(undoRec.lsn());

                if (clr == null && plan.needsAbortTx.contains(txId)) {
                    clr = logManager.logCompensation(txId, undoRec.lsn());
                    logManager.ensureDurable(clr.lsn());
                    clrByOriginalLsn.put(undoRec.lsn(), clr);
                }

                long lsnToUse = clr == null ? undoRec.lsn() : clr.lsn();
                applier.undo(undoRec, lsnToUse);
            }
            return null;
        });

        inPhase(RecoveryError.REDO_FAILED, "Redo phase failed", () -> {
            for (LogRecord redoRec : plan.redoChanges) {
                applier.apply(redoRec);
            }
            return null;
        });

        inPhase(RecoveryError.FINALIZE_FAILED, "Transaction finalization failed", () -> {
            for (Long txId : plan.needsAbortTx) {
                logManager.logAbort(txId);
                logManager.ensureDurable(logManager.getLastLsn());
            }
            return null;
        });

        inPhase(RecoveryError.FLUSH_FAILED, "Flushing recovered data failed", () -> {
            sm.flushAll();
            return null;
        });

        long maxTxId = inPhase(
                RecoveryError.FINALIZE_FAILED,
                "Reading the recovered transaction high-water mark failed",
                logManager::getMaxTxId);
        return new RecoveryResult(
                Math.max(plan.maxTxId, maxTxId),
                plan.lastTimestamp,
                plan.lastRecoveredLSN,
                plan.checkpointLsn == null
                        ? Optional.empty()
                        : Optional.of(plan.checkpointLsn));
    }

    private static <T> T inPhase(
            RecoveryError phase, String message, PhaseOperation<T> operation) {
        try {
            return operation.run();
        } catch (RecoveryException error) {
            throw error;
        } catch (Exception error) {
            String detail = error.getMessage();
            throw new RecoveryException(
                    phase,
                    detail == null ? message : message + ": " + detail,
                    error);
        }
    }

    @FunctionalInterface
    private interface PhaseOperation<T> {
        T run() throws Exception;
    }
}
