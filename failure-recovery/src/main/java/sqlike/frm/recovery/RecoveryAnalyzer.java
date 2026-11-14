package sqlike.frm.recovery;

import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;

import java.util.*;

public class RecoveryAnalyzer {

    public RecoveryPlan analyze(List<LogRecord> records) throws Exception {
        if (records == null) {
            throw new IllegalArgumentException("Daftar log tidak boleh null.");
        }

        RecoveryPlan plan = new RecoveryPlan();
        plan.committedTx = new HashSet<>();
        plan.uncommittedTx = new HashSet<>();
        plan.abortedTx = new HashSet<>();
        plan.needsAbortTx = new HashSet<>();
        plan.undoChanges = new ArrayList<>();
        plan.redoChanges = new ArrayList<>();
        
        if (records.isEmpty()) {
            plan.startLsn = 1;
            plan.lastRecoveredLSN = 0; 
            plan.maxTxId = 0; 
            plan.lastTimestamp = 0;
            return plan;
        }

        Set<Long> abortedTx = new HashSet<>();
        Map<Long, LogRecord> recordsByLsn = new HashMap<>();

        // ekstraksi info global & klasifikasi awal
        for (LogRecord record : records) {
            if (record == null) {
                throw new IllegalArgumentException("Daftar log tidak boleh berisi null.");
            }
            if (recordsByLsn.putIfAbsent(record.lsn(), record) != null) {
                throw new Exception("Log tidak konsisten: LSN " + record.lsn() + " duplikat.");
            }

            if (record.txID().isPresent()) {
                plan.maxTxId = Math.max(plan.maxTxId, record.txID().get());
            }
            
            long timeMillis = record.time().toEpochMilli();
            if (timeMillis > plan.lastTimestamp) {
                plan.lastTimestamp = timeMillis;
            }
            
            plan.lastRecoveredLSN = Math.max(plan.lastRecoveredLSN, record.lsn());

            if (record.txID().isPresent()) {
                if (record.type() == LogType.COMMIT) {
                    plan.committedTx.add(record.txID().get());
                } else if (record.type() == LogType.ABORT) {
                    abortedTx.add(record.txID().get());
                }
            }
        }

        // Pastikan setiap CLR merujuk ke perubahan asli milik transaksi yang sama.
        Set<Long> compensatedLsns = new HashSet<>();
        for (LogRecord record : records) {
            if (record.type() != LogType.COMPENSATION) {
                continue;
            }

            long originalLsn = record.undoOfLSN().orElseThrow();
            LogRecord original = recordsByLsn.get(originalLsn);
            if (original == null
                    || original.txID().isEmpty()
                    || !original.txID().equals(record.txID())
                    || !isChangeRecord(original.type())) {
                throw new Exception("Log tidak konsisten: CLR LSN " + record.lsn()
                        + " tidak merujuk ke perubahan transaksi yang sesuai.");
            }
            if (plan.committedTx.contains(record.txID().orElseThrow())) {
                throw new Exception("Log tidak konsisten: transaksi " + record.txID().get()
                        + " memiliki CLR dan COMMIT.");
            }
            if (!compensatedLsns.add(originalLsn)) {
                throw new Exception("Log tidak konsisten: perubahan LSN " + originalLsn
                        + " memiliki lebih dari satu CLR.");
            }
        }

        // cek konflik
        for (Long txId : plan.committedTx) {
            if (abortedTx.contains(txId)) {
                throw new Exception("Log tidak konsisten: TxId " + txId + " punya COMMIT dan ABORT.");
            }
        }

        // mencari checkpoint terakhir
        plan.checkpointLsn = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            LogRecord rec = records.get(i);
            if (rec.type() == LogType.CHECKPOINT && rec.checkpoint().isPresent()) {
                plan.checkpointLsn = rec.lsn();
                plan.startLsn = rec.checkpoint().get().flushedThroughLSN() + 1; 
                break;
            }
        }
        if (plan.checkpointLsn == null) plan.startLsn = 1;

        // klasifikasi uncommitted & needsAbort
        for (LogRecord rec : records) {
            if (rec.txID().isEmpty() || plan.committedTx.contains(rec.txID().get())) continue;
            
            Long txId = rec.txID().get();
            if (abortedTx.contains(txId)) {
                plan.abortedTx.add(txId);
            } else {
                plan.uncommittedTx.add(txId);
                plan.needsAbortTx.add(txId);
            }
        }

        // memilah perubahan (undo/redo)
        for (LogRecord rec : records) {
            if (rec.lsn() < plan.startLsn) continue;
            
            if (rec.type() == LogType.INSERT || rec.type() == LogType.UPDATE || rec.type() == LogType.DELETE) {
                if (rec.txID().isPresent() && plan.committedTx.contains(rec.txID().get())) {
                    plan.redoChanges.add(rec);
                } else {
                    plan.undoChanges.add(rec);
                }
            }
        }

        // sorting
        plan.redoChanges.sort(Comparator.comparingLong(LogRecord::lsn)); // naik
        plan.undoChanges.sort((r1, r2) -> Long.compare(r2.lsn(), r1.lsn())); // turun

        return plan;
    }

    private static boolean isChangeRecord(LogType type) {
        return type == LogType.INSERT
                || type == LogType.UPDATE
                || type == LogType.DELETE
                || type == LogType.SCHEMA_CHANGE;
    }
}