package sqlike.qp;

import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;

/**
 * Domain exception for Query Processor carrying structured ErrorInfo.
 */
public class QpException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient ErrorInfo errorInfo;

    public QpException(ErrorCode code, String message) {
        super(message);
        this.errorInfo = new ErrorInfo(code, ComponentType.QP, message, java.util.Optional.empty());
    }

    public QpException(ErrorInfo errorInfo) {
        super(errorInfo.message());
        this.errorInfo = errorInfo;
    }

    public ErrorInfo getErrorInfo() {
        return errorInfo;
    }
}
