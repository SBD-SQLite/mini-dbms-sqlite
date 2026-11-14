package sqlike.frm.recovery;

import java.util.Objects;

import sqlike.frm.RecoveryError;

public final class RecoveryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final RecoveryError recoveryError;

    public RecoveryException(RecoveryError recoveryError, String message, Throwable cause) {
        super(message, cause);
        this.recoveryError = Objects.requireNonNull(recoveryError, "recoveryError");
    }

    public RecoveryError getRecoveryError() {
        return recoveryError;
    }
}
