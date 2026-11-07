package sqlike.frm.pitr;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import sqlike.frm.checkpoint.SnapshotCatalog;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;

/**
 * Builds a {@link HistoricalRecoveryPlan} from the log without touching data.
 *
 * <p>TIME t targets the last record with time {@code <= t}; TRANSACTION x targets x's COMMIT.
 * Only transactions committed at or before the target are replayed. Abandoned WAL ranges are
 * skipped except when computing {@code maxTxId}.
 */
public class HistoricalRecoveryPlanner {

    private final SnapshotCatalog snapshotCatalog;

    public HistoricalRecoveryPlanner(SnapshotCatalog snapshotCatalog) {
        this.snapshotCatalog = Objects.requireNonNull(snapshotCatalog, "snapshotCatalog");
    }

    public HistoricalRecoveryPlan plan(RecoveryTarget target, List<LogRecord> history) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(history, "history");

        List<LogRecord> visible = new ArrayList<>();
        long maxTxId = 0;
        for (LogRecord record : history) {
            if (record.txID().isPresent()) {
                maxTxId = Math.max(maxTxId, record.txID().orElseThrow());
            }
            if (!snapshotCatalog.isAbandoned(record.lsn())) {
                visible.add(record);
            }
        }

        long targetLSN = resolveTargetLsn(target, visible);
        SnapshotCatalog.SnapshotEntry base = snapshotCatalog.latestUsableFor(targetLSN)
                .orElseThrow(() -> new IllegalStateException(
                        "no base snapshot at or before LSN " + targetLSN
                                + "; run a checkpoint before the target point"));

        Set<Long> committed = new HashSet<>();
        for (LogRecord record : visible) {
            if (record.type() == LogType.COMMIT && record.lsn() <= targetLSN) {
                committed.add(record.txID().orElseThrow());
            }
        }

        List<LogRecord> changes = new ArrayList<>();
        for (LogRecord record : visible) {
            if (isDataChange(record.type())
                    && record.lsn() > base.baseLSN()
                    && record.lsn() <= targetLSN
                    && committed.contains(record.txID().orElseThrow())) {
                changes.add(record);
            }
        }
        changes.sort((a, b) -> Long.compare(a.lsn(), b.lsn()));

        return new HistoricalRecoveryPlan(
                base.snapshotId(), base.baseLSN(), targetLSN, maxTxId, committed, changes);
    }

    static long resolveTargetLsn(RecoveryTarget target, List<LogRecord> visible) {
        if (target.kind() == RecoveryTargetKind.TIME) {
            Instant time = target.time().orElseThrow();
            long lsn = 0;
            for (LogRecord record : visible) {
                if (!record.time().isAfter(time)) {
                    lsn = Math.max(lsn, record.lsn());
                }
            }
            return lsn;
        }
        long txId = target.txId().orElseThrow();
        for (LogRecord record : visible) {
            if (record.type() == LogType.COMMIT && record.txID().orElse(-1L) == txId) {
                return record.lsn();
            }
        }
        throw new IllegalArgumentException(
                "transaction " + txId + " has no COMMIT on the current timeline");
    }

    private static boolean isDataChange(LogType type) {
        return type == LogType.INSERT || type == LogType.UPDATE || type == LogType.DELETE;
    }
}
