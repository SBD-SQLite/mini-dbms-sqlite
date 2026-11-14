package sqlike.frm.driver;

/** Where a scenario kills the process to simulate a crash (FR-08). */
public enum CrashPoint {
    /** Committed changes are durable in the log but their pages were never flushed. */
    AFTER_COMMIT_BEFORE_FLUSH,
    /** Pages of an unfinished transaction reached disk (STEAL) before it committed. */
    AFTER_FLUSH_BEFORE_COMMIT,
    /** A transaction finished its abort; its undone pages may not be on disk yet. */
    AFTER_ABORT_BEFORE_FLUSH,
    /** A checkpoint completed and more work happened after it. */
    AFTER_CHECKPOINT,
    /** Point-in-time recovery returned; the process dies before a normal shutdown. */
    AFTER_POINT_IN_TIME_RECOVERY
}
