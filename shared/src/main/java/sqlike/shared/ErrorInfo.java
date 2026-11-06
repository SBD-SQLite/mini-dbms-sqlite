package sqlike.shared;

import java.util.Optional;

public record ErrorInfo(
        ErrorCode code,
        ComponentType component,
        String message,
        Optional<Integer> position
) {
}
