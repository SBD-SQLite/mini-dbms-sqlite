package sqlike.shared;

import java.util.Optional;

public record AccessResponse(
        AccessStatus status,
        Optional<Long> changeLSN,
        Optional<String> waitToken,
        Optional<ErrorInfo> error) {

    public AccessResponse {
        changeLSN = changeLSN == null ? Optional.empty() : changeLSN;
        waitToken = waitToken == null ? Optional.empty() : waitToken;
        error = error == null ? Optional.empty() : error;
    }
}
