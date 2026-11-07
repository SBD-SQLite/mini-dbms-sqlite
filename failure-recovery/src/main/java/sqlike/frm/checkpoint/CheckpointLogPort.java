package sqlike.frm.checkpoint;

/** Log operations required by the checkpoint coordinator. */
public interface CheckpointLogPort {

    /** Returns the latest allocated LSN, or zero when the log is empty. */
    long getLastLsn();
    
    /**
     * Memastikan semua log record sampai LSN yang ditentukan sudah persisten ke disk.
     */
    void ensureDurable(long lsn);

    /** Persists a completed-checkpoint marker and returns its metadata. */
    CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId);
}
