package sqlike.shared;

import java.util.Optional;

public record Session(String sessionId, Optional<Long> activeTxId) {
    public Session {
        activeTxId = activeTxId == null ? Optional.empty() : activeTxId;
    }
}
