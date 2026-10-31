package sqlike.frm;

/** Whether FRM may serve transactions; only READY accepts logging requests (FR-07). */
public enum RecoveryState {
    NOT_STARTED, RUNNING, READY, FAILED
}
