package sqlike.frm;

public enum RecoveryError {
    LOG_READ_FAILED,
    LOG_INCONSISTENT,
    UNDO_FAILED,
    REDO_FAILED,
    FLUSH_FAILED
}