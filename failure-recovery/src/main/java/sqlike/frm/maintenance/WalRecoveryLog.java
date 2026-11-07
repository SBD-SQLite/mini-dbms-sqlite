package sqlike.frm.maintenance;

import java.util.List;
import java.util.Objects;

import sqlike.frm.checkpoint.CheckpointInfo;
import sqlike.frm.checkpoint.CheckpointLogPort;
import sqlike.frm.log.LogManager;
import sqlike.frm.pitr.HistoricalLogPort;
import sqlike.frm.pitr.LogRecord;

/**
 * Adapter yang menghubungkan LogManager (WAL) ke CheckpointLogPort dan HistoricalLogPort.
 */
public class WalRecoveryLog implements CheckpointLogPort, HistoricalLogPort {

    private final LogManager logManager;

    public WalRecoveryLog(LogManager logManager) {
        this.logManager = Objects.requireNonNull(logManager, "logManager");
    }

    @Override
    public long getLastLsn() {
        return logManager.getLastLsn();
    }

    @Override
    public void ensureDurable(long lsn) {
        logManager.ensureDurable(lsn);
    }

    @Override
    public CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId) {
        long cpLsn = logManager.logCheckpoint(flushedThroughLSN);
        return new CheckpointInfo(cpLsn, flushedThroughLSN, java.time.Instant.now(), snapshotId);
    }

    @Override
    public List<LogRecord> readHistory() {
        return logManager.readLogs(null);
    }

    @Override
    public void separateHistory() {
        logManager.archiveActiveLog();
        logManager.startNewLog();
    }
}