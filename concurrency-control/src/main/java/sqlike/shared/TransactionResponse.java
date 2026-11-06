package sqlike.shared;

import java.util.Optional;

public record TransactionResponse(
        long txId,
        long timestamp,
        TransactionStatus status,
        Optional<ErrorInfo> error) {

    public TransactionResponse {
        error = error == null ? Optional.empty() : error;
    }
}
