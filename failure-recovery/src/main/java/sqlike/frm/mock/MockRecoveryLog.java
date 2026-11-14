package sqlike.frm.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import sqlike.frm.checkpoint.CheckpointInfo;
import sqlike.frm.checkpoint.CheckpointLogPort;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.pitr.HistoricalLogPort;
import sqlike.shared.DataChange;

/**
 * In-memory write-ahead log for checkpoint and point-in-time recovery tests (FR-09).
 *
 * <p>MOCK: TODO(FR2): replace with {@code WalRecoveryLog} over the real {@code LogManager}.
 */
public class MockRecoveryLog implements CheckpointLogPort, HistoricalLogPort {

    private final List<LogRecord> archived = new ArrayList<>();
    private final List<LogRecord> active = new ArrayList<>();
    private final Supplier<Instant> clock;
    private long lastLsn;
    private long durableLsn;
    private boolean failDurability;

    public MockRecoveryLog() {
        this(Instant::now);
    }

    public MockRecoveryLog(Supplier<Instant> clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized long logBegin(long txId) {
        return append(Optional.of(txId), LogType.BEGIN, Optional.empty(), Optional.empty());
    }

    public synchronized long logChange(long txId, DataChange change) {
        LogType type = switch (change.operation()) {
            case INSERT -> LogType.INSERT;
            case UPDATE -> LogType.UPDATE;
            case DELETE -> LogType.DELETE;
        };
        return append(Optional.of(txId), type, Optional.of(change), Optional.empty());
    }

    public synchronized long logCommit(long txId) {
        long lsn = append(Optional.of(txId), LogType.COMMIT, Optional.empty(), Optional.empty());
        ensureDurable(lsn);
        return lsn;
    }

    public synchronized long logAbort(long txId) {
        long lsn = append(Optional.of(txId), LogType.ABORT, Optional.empty(), Optional.empty());
        ensureDurable(lsn);
        return lsn;
    }

    @Override
    public synchronized long getLastLsn() {
        return lastLsn;
    }

    @Override
    public synchronized void ensureDurable(long lsn) {
        if (failDurability) {
            throw new IllegalStateException("MOCK: simulated log durability failure");
        }
        if (lsn > lastLsn) {
            throw new IllegalArgumentException("LSN " + lsn + " was never allocated");
        }
        durableLsn = Math.max(durableLsn, lsn);
    }

    @Override
    public synchronized CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId) {
        long lsn = lastLsn + 1L;
        CheckpointInfo info = new CheckpointInfo(lsn, flushedThroughLSN, clock.get(), snapshotId);
        append(Optional.empty(), LogType.CHECKPOINT, Optional.empty(), Optional.of(info));
        return info;
    }

    @Override
    public synchronized List<LogRecord> readHistory() {
        List<LogRecord> all = new ArrayList<>(archived);
        all.addAll(active);
        return all;
    }

    @Override
    public synchronized void separateHistory(String snapshotId, long targetLSN) {
        if (targetLSN < 0 || targetLSN > lastLsn) {
            throw new IllegalArgumentException("target LSN " + targetLSN + " is outside the log");
        }
        archived.addAll(active);
        active.clear();
        CheckpointInfo marker = new CheckpointInfo(lastLsn + 1L, lastLsn, clock.get(), snapshotId);
        append(Optional.empty(), LogType.CHECKPOINT, Optional.empty(), Optional.of(marker));
    }

    public synchronized List<LogRecord> activeLog() {
        return List.copyOf(active);
    }

    public synchronized long getDurableLsn() {
        return durableLsn;
    }

    public synchronized void simulateDurabilityFailure(boolean fail) {
        this.failDurability = fail;
    }

    private long append(Optional<Long> txId, LogType type, Optional<DataChange> change,
            Optional<CheckpointInfo> checkpoint) {
        long lsn = ++lastLsn;
        active.add(new LogRecord(lsn, clock.get(), txId, type, change, checkpoint));
        return lsn;
    }
}
