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
 * IDs are recorded in {@code SnapshotCatalog}; the CHECKPOINT record also carries them.
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
        return wal.logCheckpoint(flushedThroughLSN, snapshotId);
    }

    /** Every epoch, archived ones first, in LSN order. */
    @Override
    public List<LogRecord> readHistory() {
        return wal.readHistory();
    }

    /**
     * Archives the old timeline and opens a new WAL epoch whose LSNs continue after the last one.
     * Snapshots are tracked by {@code SnapshotCatalog}, so the epoch marker only names the cut.
     */
    @Override
    public void separateHistory() {
        long boundary = wal.getLastLsn();
        wal.archiveActiveLog();
        wal.startNewLog(EPOCH_MARKER_PREFIX + boundary, boundary);
    }

    /** Label of the CHECKPOINT marker that starts a new epoch after PITR. */
    public static final String EPOCH_MARKER_PREFIX = "pitr-cut-";
}
