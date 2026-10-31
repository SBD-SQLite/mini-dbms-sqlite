package sqlike.qp;

import java.util.Optional;

/**
 * Execution context carrying transaction ID, session info, and runtime state during query execution.
 */
public record ExecutionContext(long txId, Optional<String> sessionId) {
    public ExecutionContext {
        sessionId = sessionId == null ? Optional.empty() : sessionId;
    }

    public static ExecutionContext of(long txId) {
        return new ExecutionContext(txId, Optional.empty());
    }

    public static ExecutionContext of(long txId, String sessionId) {
        return new ExecutionContext(txId, Optional.ofNullable(sessionId));
    }
}
