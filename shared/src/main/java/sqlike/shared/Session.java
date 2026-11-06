package sqlike.shared;

import java.util.Optional;

public record Session(
        String sessionId,
        Optional<Long> activeTxId
) {
}
