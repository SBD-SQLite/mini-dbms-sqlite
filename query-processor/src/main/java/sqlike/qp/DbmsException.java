package sqlike.qp;

import sqlike.qp.port.QpContracts.DbmsError;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;

/**
 * Runtime exception wrapper used by ExecutionEngine and QP components.
 */
public class DbmsException extends QpException {
    public DbmsException(String message) {
        super(ErrorCode.INVALID_STATE, message);
    }

    public DbmsException(String message, Throwable cause) {
        super(ErrorCode.INVALID_STATE, message);
        if (cause != null) {
            initCause(cause);
        }
    }

    public DbmsException(DbmsError error) {
        super(ErrorCode.INVALID_STATE, error != null ? error.message() : "DBMS execution error");
    }

    public DbmsException(ErrorInfo errorInfo) {
        super(errorInfo);
    }

    public DbmsError toDbmsError() {
        return new DbmsError(getMessage());
    }
}

