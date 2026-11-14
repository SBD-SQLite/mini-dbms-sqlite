package sqlike.ccm;

/** An FRM call failed. The facade reports it to QP as {@code ErrorCode.WAL_FAILURE}. */
public class FrmFailureException extends RuntimeException {

    private final String operation;

    public FrmFailureException(String operation, Throwable cause) {
        super("FRM " + operation + " failed: " + cause.getMessage(), cause);
        this.operation = operation;
    }

    public FrmFailureException(String operation, String message) {
        super("FRM " + operation + " failed: " + message);
        this.operation = operation;
    }

    /** The FRM method that failed, for example {@code logChange} or {@code rollback}. */
    public String operation() {
        return operation;
    }
}
