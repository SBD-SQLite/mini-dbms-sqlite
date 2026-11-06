package sqlike.frm.mock;

import java.time.Instant;
import java.util.Objects;

import sqlike.frm.checkpoint.CheckpointInfo;
import sqlike.frm.checkpoint.CheckpointLogPort;
import sqlike.frm.pitr.HistoricalLogPort;
import sqlike.frm.pitr.HistoricalRecoveryPlan;
import sqlike.frm.port.SmPort;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;

/** FR4-owned Milestone 1 stand-in for checkpoint metadata and historical WAL access. */
public class MockRecoveryLog implements CheckpointLogPort, HistoricalLogPort {

    private long lastCheckpointLSN;
    private CheckpointInfo latestCheckpoint;

    @Override
    public synchronized long getLastLsn() {
        return lastCheckpointLSN;
    }

    @Override
    public synchronized CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId) {
        long checkpointLSN = Math.max(lastCheckpointLSN + 1L, flushedThroughLSN + 1L);
        latestCheckpoint = new CheckpointInfo(
                checkpointLSN, flushedThroughLSN, Instant.now(), snapshotId);
        lastCheckpointLSN = checkpointLSN;
        return latestCheckpoint;
    }

    @Override
    public synchronized HistoricalRecoveryPlan planFor(RecoveryTarget target) {
        Objects.requireNonNull(target, "target");
        if (latestCheckpoint == null) {
            throw new IllegalStateException("no completed checkpoint is available");
        }
        long maxTxId = target.kind() == RecoveryTargetKind.TRANSACTION
                ? target.txId().orElseThrow()
                : 0L;
        return new HistoricalRecoveryPlan(
                latestCheckpoint.snapshotId(), latestCheckpoint.checkpointLSN(), maxTxId);
    }

    @Override
    public void replay(HistoricalRecoveryPlan plan, SmPort storageManager) {
        // MOCK(FR4): Milestone 2 will request and replay committed WAL changes through targetLSN.
    }
}
