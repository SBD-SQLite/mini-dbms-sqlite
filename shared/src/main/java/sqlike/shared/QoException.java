package sqlike.shared;

/**
 * Domain exception thrown by the Query Optimizer; carries an ErrorInfo
 * (code, component QO, message, optional position). Unchecked.
 */
public class QoException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient ErrorInfo info;

    public QoException(ErrorInfo info) { super(info.message()); this.info = info; }

    public ErrorInfo getInfo() { return info; }
}
