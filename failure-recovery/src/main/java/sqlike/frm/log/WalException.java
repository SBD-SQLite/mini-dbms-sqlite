package sqlike.frm.log;

public final class WalException extends RuntimeException {
    private final boolean recoveryRequired;
    private final boolean commitUnknown;

    public WalException(String message, Throwable cause, boolean recoveryRequired, boolean commitUnknown) {
        super(message, cause);
        this.recoveryRequired = recoveryRequired;
        this.commitUnknown = commitUnknown;
    }

    public boolean isRecoveryRequired() {
        return recoveryRequired;
    }

    public boolean isCommitUnknown() {
        return commitUnknown;
    }
}