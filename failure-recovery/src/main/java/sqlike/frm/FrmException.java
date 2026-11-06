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

    public FrmException(ErrorInfo info, Throwable cause) {
        super(Objects.requireNonNull(info, "info").message(), cause);
        this.info = info;
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
}
