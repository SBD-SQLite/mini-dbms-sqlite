package sqlike.frm.checkpoint;

/** Write-ahead-log operations a checkpoint needs. */
public interface CheckpointLogPort {

    /** Returns the latest allocated LSN, or zero when the log is empty. */
    long getLastLsn();

    /**
     * Returns only after every log record up to and including {@code lsn} is on stable storage.
     * Throws when durability cannot be guaranteed; the checkpoint then stops before touching data.
     */
    void ensureDurable(long lsn);

    /**
     * Appends a completed-checkpoint marker and returns its metadata. The caller makes the marker
     * durable with {@link #ensureDurable(long)} before treating the checkpoint as complete.
     */
    CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId);
}
