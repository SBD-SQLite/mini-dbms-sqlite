package sqlike.frm.adapter;

import java.util.Objects;

import sqlike.frm.checkpoint.CheckpointInfo;
import sqlike.frm.checkpoint.CheckpointLogPort;
import sqlike.frm.log.LogManager;

/**
 * Connects FR4's {@code CheckpointManager} to FR2's {@link LogManager}.
 *
 * <p>Enforces the PRD §15 order around FR4's coordinator: the log is durable up to the checkpoint
 * boundary before SM flushes, and the completed-checkpoint marker is durable before the checkpoint
 * is reported as finished. {@code CheckpointManager} reads the boundary right before
 * {@code flushAll}, so the boundary is made durable at that moment.
 */
public class LogCheckpointAdapter implements CheckpointLogPort {

    private final LogManager log;

    public LogCheckpointAdapter(LogManager log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public long getLastLsn() {
        long boundary = log.getLastLsn();
        if (boundary > 0) {
            log.ensureDurable(boundary);
        }
        return boundary;
    }

    @Override
    public CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId) {
        // TODO(FR2/FR4): LogManager.logCheckpoint should also store snapshotId so recovery and
        // PITR can find the snapshot that matches this checkpoint.
        CheckpointInfo written = log.logCheckpoint(flushedThroughLSN);
        log.ensureDurable(written.checkpointLSN());
        return new CheckpointInfo(
                written.checkpointLSN(), flushedThroughLSN, written.createdAt(), snapshotId);
    }
}
