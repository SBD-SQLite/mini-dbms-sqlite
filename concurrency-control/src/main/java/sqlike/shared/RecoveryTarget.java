package sqlike.shared;

import java.time.Instant;
import java.util.Optional;

public record RecoveryTarget(RecoveryTargetKind kind, Optional<Instant> time, Optional<Long> txId) {
}
