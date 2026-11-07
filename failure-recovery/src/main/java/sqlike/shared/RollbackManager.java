package sqlike.frm.recovery;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

// ==========================================
// 1. ENUMERATION & DATA TYPES (FR3 & PITR)
// ==========================================

public enum RollbackStatus {
    ROLLED_BACK,
    ALREADY_ROLLED_BACK,
    ALREADY_COMMITTED,
    UNKNOWN_TX,
    FAILED
}

public enum RecoveryTargetKind {
    TIME,
    TRANSACTION,
    LSN
}

public class RecoveryTarget {
    public RecoveryTargetKind kind;
    public LocalDateTime time;
    public Integer txId;
    public Integer targetLsn;

    public RecoveryTarget() {}
    
    public RecoveryTarget(LocalDateTime time, Integer txId, Integer targetLsn) {
        if (time != null) {
            this.kind = RecoveryTargetKind.TIME;
            this.time = time;
        } else if (txId != null) {
            this.kind = RecoveryTargetKind.TRANSACTION;
            this.txId = txId;
        } else {
            this.kind = RecoveryTargetKind.LSN;
            this.targetLsn = targetLsn;
        }
    }
}

public class SnapshotInfo {
    public String snapshotId;
    public int baseLsn;
    public LocalDateTime createdAt;

    public SnapshotInfo() {}

    public SnapshotInfo(String snapshotId, int baseLsn, LocalDateTime createdAt) {
        this.snapshotId = snapshotId;
        this.baseLsn = baseLsn;
        this.createdAt = createdAt;
    }
}

public class LogRecord {
    public int lsn;
    public int prevLsn;
    public int txId;
    public String type; // "BEGIN", "UPDATE", "INSERT", "DELETE", "COMMIT", "ABORT", "CHECKPOINT"
    public String tableName;
    public int rowId;
    public String oldValue;
    public String newValue;
    public LocalDateTime timestamp;

    public LogRecord(int lsn, int prevLsn, int txId, String type, String tableName, int rowId, String oldValue, String newValue, LocalDateTime timestamp) {
        this.lsn = lsn;
        this.prevLsn = prevLsn;
        this.txId = txId;
        this.type = type;
        this.tableName = tableName;
        this.rowId = rowId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.timestamp = timestamp;
    }
}

public class PitrPlan {
    public String snapshotId;
    public int targetLsn;
    public int baseLsn;
    public List<LogRecord> redoChanges;
    public Set<Integer> committedTxIds;
    public int maxTxId;

    public PitrPlan() {
        this.redoChanges = new ArrayList<>();
        this.committedTxIds = new HashSet<>();
    }
}

public class RecoveryReport {
    public boolean success;
    public int errorCode;
    public String errorMessage;
    public int txId;
    public int undoCount;
    public int redoCount;
    public int lastLsn;
    public int maxTxId;

    public RecoveryReport() {}
}

// ==========================================
// MOCK / PORT ABSTRACTIONS
// ==========================================

public interface LogManager {
    List<LogRecord> readHistory();
    int appendLog(int txId, String type, String table, int rowId, String oldVal, String newVal);
    void ensureDurable(int lsn);
    void separateHistory();
    int getLastLsn();
}

public interface SmPort {
    void flushAll();
    String createSnapshot();
    void restoreSnapshot(String snapshotId);
    void writeAt(String tableName, int rowId, String value, int lsn);
}

public interface CcmPort {
    void pauseNewTransactions();
    boolean waitUntilIdle(long timeoutMs);
    void resumeTransactions();
    void initAfterRecovery(int maxTxId);
}

public interface ChangeApplier {
    void applyChange(LogRecord change);
    void revertChange(LogRecord change);
}

public interface RecoveryManager {
    boolean isTransactionActive(int txId);
    boolean isTransactionCommitted(int txId);
    boolean isTransactionAborted(int txId);
    void markAborted(int txId);
}

// ==========================================
// 2. CLASS ROLLBACK MANAGER (FR3)
// ==========================================

public class RollbackManager {
    private final LogManager logManager;
    private final RecoveryManager recoveryManager;
    private final SmPort sm;
    private final CcmPort ccm;

    public RollbackManager(LogManager logManager, RecoveryManager recoveryManager, SmPort sm, CcmPort ccm) {
        this.logManager = logManager;
        this.recoveryManager = recoveryManager;
        this.sm = sm;
        this.ccm = ccm;
    }

    /**
     * Membatalkan perubahan transaksi tertentu (Undo + Log ABORT).
     */
    public RollbackStatus rollback(int txId) {
        if (recoveryManager.isTransactionCommitted(txId)) {
            return RollbackStatus.ALREADY_COMMITTED;
        }
        if (recoveryManager.isTransactionAborted(txId)) {
            return RollbackStatus.ALREADY_ROLLED_BACK;
        }

        try {
            // 1. Batalkan semua perubahan di buffer/storage (Backward Scan Undo)
            int undoneRecords = undoTxChanges(txId);
            
            // 2. Tulis penanda ABORT ke WAL
            writeAbortLog(txId);
            
            // 3. Update state internal transaksi
            recoveryManager.markAborted(txId);

            return RollbackStatus.ROLLED_BACK;
        } catch (Exception e) {
            return RollbackStatus.FAILED;
        }
    }

    /**
     * Mengembalikan nilai data sebelum transaksi dieksekusi (undo).
     * Membaca log dari belakang ke depan (Backward Scan).
     */
    public int undoTxChanges(int txId) {
        List<LogRecord> history = logManager.readHistory();
        int undoCount = 0;

        // Scan dari belakang untuk memulihkan old_value
        for (int i = history.size() - 1; i >= 0; i--) {
            LogRecord rec = history.get(i);
            if (rec.txId == txId && ("UPDATE".equals(rec.type) || "INSERT".equals(rec.type) || "DELETE".equals(rec.type))) {
                // Revert state pada Storage Manager
                sm.writeAt(rec.tableName, rec.rowId, rec.oldValue, rec.lsn);
                undoCount++;
            }
        }
        return undoCount;
    }

    /**
     * Mencatat log ABORT ke dalam WAL dan memastikan durabilitas.
     */
    public int writeAbortLog(int txId) {
        int abortLsn = logManager.appendLog(txId, "ABORT", null, -1, null, null);
        logManager.ensureDurable(abortLsn);
        return abortLsn;
    }
}

// ==========================================
// 3. CLASS POINT IN TIME RECOVERY (PITR)
// ==========================================

public class PointInTimeRecovery {
    private final LogManager logManager;
    private final SmPort sm;
    private final CcmPort ccm;
    private final ChangeApplier applier;
    private final SnapshotInfo snapshot;

    public PointInTimeRecovery(LogManager logManager, SmPort sm, CcmPort ccm, ChangeApplier applier, SnapshotInfo snapshot) {
        this.logManager = logManager;
        this.sm = sm;
        this.ccm = ccm;
        this.applier = applier;
        this.snapshot = snapshot;
    }

    /**
     * Memulai alur Point-in-Time Recovery menuju target tertentu.
     */
    public RecoveryReport recoverTo(RecoveryTarget target) {
        RecoveryReport report = new RecoveryReport();
        
        try {
            // Step 1: Quiesce sistem (Pause transaksi baru dan tunggu aktif selesai)
            ccm.pauseNewTransactions();
            if (!ccm.waitUntilIdle(10000)) { // Timeout 10 detik
                report.success = false;
                report.errorCode = 408;
                report.errorMessage = "Timeout waiting for active transactions to quiesce.";
                ccm.resumeTransactions();
                return report;
            }

            // Step 2: Tentukan target LSN
            int targetLsn = resolveTargetLsn(target);
            if (targetLsn < 0) {
                report.success = false;
                report.errorCode = 400;
                report.errorMessage = "Invalid target or target transaction was not COMMITTED.";
                ccm.resumeTransactions();
                return report;
            }

            // Step 3: Susun rencana pemulihan (PitrPlan)
            PitrPlan plan = buildPlan(targetLsn);

            // Step 4: Restorasi Snapshot Dasar
            restoreBaseSnapshot(plan);

            // Step 5: Replay perubahan transaksi yang COMMITTED
            int replayed = replayCommitted(plan);
            report.redoCount = replayed;

            // Step 6: Flush Storage Manager
            sm.flushAll();

            // Step 7: Pisahkan riwayat log lama dan buat timeline baru
            finalizeHistory(plan);

            // Step 8: Reset Concurrency Control Manager & Resume
            ccm.initAfterRecovery(plan.maxTxId);
            ccm.resumeTransactions();

            report.success = true;
            report.targetLsn = plan.targetLsn;
            report.lastLsn = logManager.getLastLsn();
            report.maxTxId = plan.maxTxId;

        } catch (Exception e) {
            report.success = false;
            report.errorCode = 500;
            report.errorMessage = "PITR Execution Error: " + e.getMessage();
            ccm.resumeTransactions();
        }

        return report;
    }

    /**
     * Menentukan batas LSN berdasarkan timestamp atau tx_id target.
     */
    public int resolveTargetLsn(RecoveryTarget target) {
        List<LogRecord> history = logManager.readHistory();
        if (history.isEmpty()) return 0;

        if (target.kind == RecoveryTargetKind.LSN) {
            return target.targetLsn != null ? target.targetLsn : 0;
        }

        if (target.kind == RecoveryTargetKind.TRANSACTION) {
            for (LogRecord rec : history) {
                if (rec.txId == target.txId && "COMMIT".equals(rec.type)) {
                    return rec.lsn;
                }
            }
            return -1; // Transaksi tidak ditemukan atau tidak COMMITTED
        }

        if (target.kind == RecoveryTargetKind.TIME) {
            int targetLsn = 0;
            for (LogRecord rec : history) {
                if (rec.timestamp != null && !rec.timestamp.isAfter(target.time)) {
                    targetLsn = rec.lsn;
                }
            }
            return targetLsn;
        }

        return 0;
    }

    /**
     * Membuat rencana pemulihan (PitrPlan) berdasarkan batas target LSN.
     */
    public PitrPlan buildPlan(int targetLsn) {
        PitrPlan plan = new PitrPlan();
        plan.snapshotId = snapshot != null ? snapshot.snapshotId : "default_snap";
        plan.baseLsn = snapshot != null ? snapshot.baseLsn : 0;
        plan.targetLsn = targetLsn;

        List<LogRecord> history = logManager.readHistory();
        int maxTxId = 0;

        // Pass 1: Identifikasi semua transaksi yang ter-COMMIT sebelum atau pada targetLsn
        for (LogRecord rec : history) {
            if (rec.txId > maxTxId) maxTxId = rec.txId;
            if (rec.lsn <= targetLsn && "COMMIT".equals(rec.type)) {
                plan.committedTxIds.add(rec.txId);
            }
        }
        plan.maxTxId = maxTxId;

        // Pass 2: Kumpulkan perubahan DML dari transaksi committed dalam rentang (baseLsn, targetLsn]
        for (LogRecord rec : history) {
            if (rec.lsn > plan.baseLsn && rec.lsn <= targetLsn) {
                if (plan.committedTxIds.contains(rec.txId) && 
                   ("UPDATE".equals(rec.type) || "INSERT".equals(rec.type) || "DELETE".equals(rec.type))) {
                    plan.redoChanges.add(rec);
                }
            }
        }

        return plan;
    }

    /**
     * Meminta Storage Manager memulihkan snapshot dasar.
     */
    public void restoreBaseSnapshot(PitrPlan plan) {
        sm.restoreSnapshot(plan.snapshotId);
    }

    /**
     * Menerapkan kembali (redo) log transaksi yang sudah committed sampai LSN target.
     */
    public int replayCommitted(PitrPlan plan) {
        int appliedCount = 0;
        for (LogRecord change : plan.redoChanges) {
            if (applier != null) {
                applier.applyChange(change);
            } else {
                sm.writeAt(change.tableName, change.rowId, change.newValue, change.lsn);
            }
            appliedCount++;
        }
        return appliedCount;
    }

    /**
     * Memisahkan riwayat log setelah target LSN agar tidak overlap dengan timeline baru.
     */
    public void finalizeHistory(PitrPlan plan) {
        logManager.separateHistory();
    }
}