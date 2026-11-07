package sqlike.qo;

import java.util.Optional;
import sqlike.shared.*;

public final class QoErrors {
    private QoErrors() { }

    private static QoException make(ErrorCode c, String msg, Integer pos) {
        return new QoException(new ErrorInfo(c, ComponentType.QO, msg, Optional.ofNullable(pos)));
    }
    /** Lexical/ grammar error. */
    public static QoException syntax(String msg, int position) { return make(ErrorCode.SYNTAX_ERROR, msg, position); }
    /** Unknown/ambiguous table or column, type mismatch */
    public static QoException semantic(String msg) { return make(ErrorCode.SEMANTIC_ERROR, msg, null); }
    /** Valid SQL diluar agreed contract  */
    public static QoException unsupported(String msg, int position) { return make(ErrorCode.UNSUPPORTED_OPERATION, msg, position); }
    /** Bad value*/
    public static QoException invalidValue(String msg, int position) { return make(ErrorCode.INVALID_VALUE, msg, position); }
    /** API used in the wrong state */
    public static QoException invalidState(String msg) { return make(ErrorCode.INVALID_STATE, msg, null); }
}
