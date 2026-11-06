package sqlike.frm.driver;

/** Where a scenario kills the process to simulate a crash (FR-08). */
public enum CrashPoint {
    AFTER_COMMIT_BEFORE_FLUSH,
    AFTER_FLUSH_BEFORE_COMMIT,
    DURING_ROLLBACK,
    DURING_CHECKPOINT,
    DURING_RECOVERY
}
