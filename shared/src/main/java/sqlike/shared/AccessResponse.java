package sqlike.shared;

import java.util.Optional;

public record AccessResponse(
        AccessStatus status,
        Optional<Long> changeLSN,
        Optional<String> waitToken,
        Optional<ErrorInfo> error
) {
}
