package sqlike.frm;

import java.util.Objects;
import java.util.Optional;

import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;

/**
 * Unchecked FRM domain error carrying an {@link ErrorInfo} (PRD v1.0 §2).
 *
 * <p>CCM and QP read {@link #getInfo()} instead of parsing the message.
 */
public class FrmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ErrorInfo info;
    private final boolean commitUnknown;

    public FrmException(ErrorInfo info, Throwable cause) {
        this(info, cause, false);
    }

    public FrmException(ErrorInfo info, Throwable cause, boolean commitUnknown) {
        super(Objects.requireNonNull(info, "info").message(), cause);
        this.info = info;
        this.commitUnknown = commitUnknown;
    }

    /** WAL failure whose COMMIT may or may not be durable; only recovery can tell (PRD §12). */
    public FrmException(ErrorCode code, String message, Throwable cause, boolean commitUnknown) {
        this(new ErrorInfo(code, ComponentType.FRM, message, Optional.empty()), cause, commitUnknown);
    }

    public FrmException(ErrorCode code, String message) {
        this(code, ComponentType.FRM, message, null);
    }

    public FrmException(ErrorCode code, String message, Throwable cause) {
        this(code, ComponentType.FRM, message, cause);
    }

    public FrmException(ErrorCode code, ComponentType component, String message, Throwable cause) {
        this(new ErrorInfo(code, component, message, Optional.empty()), cause);
    }

    public ErrorInfo getInfo() {
        return info;
    }

    public ErrorCode getCode() {
        return info.code();
    }

    /**
     * True when the transaction's COMMIT may already be durable. CCM must then keep the
     * transaction protected, not report success, and not undo it; the system fails stop and
     * startup recovery decides the outcome.
     */
    public boolean isCommitUnknown() {
        return commitUnknown;
    }
}
