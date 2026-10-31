package sqlike.ccm;

import java.util.Objects;

import sqlike.shared.ProtocolType;

/** Startup configuration for CCM, read by {@link ConcurrencyControlManager} (CC1). */
public record CCMConfig(ProtocolType protocol, long deadlockTimeoutMs) {

    public CCMConfig {
        Objects.requireNonNull(protocol, "protocol");
        if (deadlockTimeoutMs <= 0) {
            throw new IllegalArgumentException("deadlockTimeoutMs must be positive");
        }
    }

    /** Default configuration: lock-based protocol, deadlocks resolved within 5 seconds. */
    public static CCMConfig defaults() {
        return new CCMConfig(ProtocolType.LOCK_BASED, 5000L);
    }
}
