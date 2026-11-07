package sqlike.frm.maintenance;

import java.util.List;
import java.util.Objects;

import sqlike.frm.checkpoint.CheckpointInfo;
import sqlike.frm.checkpoint.CheckpointLogPort;
import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.pitr.HistoricalLogPort;

/**
 * {@link CheckpointLogPort} and {@link HistoricalLogPort} backed by {@link LogManager}. Snapshot
 * IDs live in {@code SnapshotCatalog}, so the CHECKPOINT record does not store them.
 */
public class WalRecoveryLog implements CheckpointLogPort, HistoricalLogPort {

    private final LogManager wal;

    public WalRecoveryLog(LogManager wal) {
        this.wal = Objects.requireNonNull(wal, "wal");
    }

    @Override
    public long getLastLsn() {
        return wal.getLastLsn();
    }

    @Override
    public void ensureDurable(long lsn) {
        wal.ensureDurable(lsn);
    }

    @Override
    public CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId) {
        CheckpointInfo logged = wal.logCheckpoint(flushedThroughLSN);
        return new CheckpointInfo(
                logged.checkpointLSN(), logged.flushedThroughLSN(), logged.createdAt(), snapshotId);
    }

    @Override
    public List<LogRecord> readHistory() {
        // TODO(FR2): LogManager.readLogs reads only the active log; archived logs must come first.
        return wal.readLogs(null);
    }

    @Override
    public void separateHistory() {
        // TODO(FR2): archiveActiveLog + startNewLog must keep LSNs continuing after the last one.
        wal.archiveActiveLog();
        wal.startNewLog();
    }
}
