package sqlike.frm.log;

import sqlike.shared.ErrorCode;

public final class WalException extends RuntimeException {
    private final ErrorCode errorCode;
    private final boolean recoveryRequired;
    private final boolean commitUnknown;

    public WalException(String message, Throwable cause, boolean recoveryRequired, boolean commitUnknown) {
        this(ErrorCode.WAL_FAILURE, message, cause, recoveryRequired, commitUnknown);
    }

    public WalException(ErrorCode errorCode, String message, Throwable cause, boolean recoveryRequired, boolean commitUnknown) {
        super(message, cause);  
        this.errorCode = errorCode;
        this.recoveryRequired = recoveryRequired;
        this.commitUnknown = commitUnknown;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean isRecoveryRequired() {
        return recoveryRequired;
    }

    public boolean isCommitUnknown() {
        return commitUnknown;
    }
}
