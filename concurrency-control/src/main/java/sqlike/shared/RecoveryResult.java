package sqlike.shared;

import java.util.Optional;

public record RecoveryResult(
        long lastTxId,
        long lastTimestamp,
        long lastRecoveredLSN,
        Optional<Long> checkpointLSN) {
}
