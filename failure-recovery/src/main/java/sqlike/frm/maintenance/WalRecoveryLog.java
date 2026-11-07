package sqlike.frm.maintenance;

import java.util.List;
import java.util.Objects;

import sqlike.frm.checkpoint.CheckpointInfo;
import sqlike.frm.checkpoint.CheckpointLogPort;
import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.pitr.HistoricalLogPort;

/** {@link CheckpointLogPort} and {@link HistoricalLogPort} backed by {@link LogManager}. */
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
        return wal.logCheckpoint(flushedThroughLSN, snapshotId);
    }

    @Override
    public List<LogRecord> readHistory() {
        return wal.readHistory();
    }

    @Override
    public void separateHistory(String snapshotId, long targetLSN) {
        wal.archiveActiveLog();
        wal.startNewLog(snapshotId, targetLSN);
    }
}
