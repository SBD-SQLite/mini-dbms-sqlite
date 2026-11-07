package sqlike.frm.pitr;

import java.time.Instant;
import java.util.*;

import sqlike.frm.checkpoint.SnapshotCatalog;
import sqlike.frm.checkpoint.SnapshotEntry;

/**
 * Menyusun rencana pemulihan historis (PITR) tanpa mengubah data di disk.
 */
public class HistoricalRecoveryPlanner {

    public static HistoricalRecoveryPlan plan(
            RecoveryTarget target,
            List<LogRecord> history,
            SnapshotCatalog catalog) {

        long targetLSN = resolveTargetLSN(target, history);
        
        // Cari snapshot dasar terbaru dengan baseLSN <= targetLSN
        SnapshotEntry baseSnapshot = catalog.findBaseSnapshotFor(targetLSN)
                .orElseThrow(() -> new IllegalStateException(
                        "Tidak ditemukan snapshot dasar yang valid untuk target LSN: " + targetLSN));

        Set<Long> committedTxIds = new HashSet<>();
        long maxTxId = 0;

        // Pass 1: Identifikasi semua transaksi COMMITTED sebelum/pada targetLSN & cari maxTxId
        for (LogRecord rec : history) {
            if (rec.txId() > maxTxId) {
                maxTxId = rec.txId();
            }
            if (rec.lsn() <= targetLSN && "COMMIT".equalsIgnoreCase(rec.type())) {
                committedTxIds.add(rec.txId());
            }
        }

        // Pass 2: Kumpulkan perubahan yang perlu di-replay (baseLSN < LSN <= targetLSN)
        List<LogRecord> changesToReplay = new ArrayList<>();
        for (LogRecord rec : history) {
            if (rec.lsn() > baseSnapshot.baseLSN() && rec.lsn() <= targetLSN) {
                if (committedTxIds.contains(rec.txId()) && rec.change() != null) {
                    changesToReplay.add(rec);
                }
            }
        }

        return new HistoricalRecoveryPlan(
                baseSnapshot.snapshotId(),
                baseSnapshot.baseLSN(),
                targetLSN,
                maxTxId,
                committedTxIds,
                changesToReplay);
    }

    private static long resolveTargetLSN(RecoveryTarget target, List<LogRecord> history) {
        if (target.kind() == RecoveryTargetKind.TRANSACTION) {
            long targetTxId = target.txId().orElseThrow();
            for (LogRecord rec : history) {
                if (rec.txId() == targetTxId && "COMMIT".equalsIgnoreCase(rec.type())) {
                    return rec.lsn();
                }
            }
            throw new IllegalArgumentException("Transaksi " + targetTxId + " tidak pernah COMMIT.");
        } else {
            Instant targetTime = target.time().orElseThrow();
            long maxLsn = 0;
            for (LogRecord rec : history) {
                if (rec.timestamp() != null && !rec.timestamp().isAfter(targetTime)) {
                    if (rec.lsn() > maxLsn) {
                        maxLsn = rec.lsn();
                    }
                }
            }
            return maxLsn;
        }
    }
}