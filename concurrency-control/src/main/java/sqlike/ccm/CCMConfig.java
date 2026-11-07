package sqlike.ccm;

import java.util.Objects;

import sqlike.shared.ProtocolType;

/** Startup configuration for CCM, read by {@link ConcurrencyControlManager} (CC1). */
public record CCMConfig(
        ProtocolType protocol,
        long deadlockTimeoutMs,
        int rollbackAttempts,
        long rollbackRetryDelayMs,
        long applyDrainTimeoutMs) {

    public CCMConfig {
        Objects.requireNonNull(protocol, "protocol");
        if (deadlockTimeoutMs <= 0) {
            throw new IllegalArgumentException("deadlockTimeoutMs must be positive");
        }
        if (rollbackAttempts < 1) {
            throw new IllegalArgumentException("rollbackAttempts must be at least 1");
        }
        if (rollbackRetryDelayMs < 0) {
            throw new IllegalArgumentException("rollbackRetryDelayMs must not be negative");
        }
        if (applyDrainTimeoutMs <= 0) {
            throw new IllegalArgumentException("applyDrainTimeoutMs must be positive");
        }
    }

    public CCMConfig(ProtocolType protocol, long deadlockTimeoutMs) {
        this(protocol, deadlockTimeoutMs, 3, 100L, 30_000L);
    }

    /**
     * Default configuration: lock-based protocol, deadlocks resolved within 5 seconds, rollback and
     * logAbort tried 3 times 100 ms apart, and a 30 second wait for an in-flight apply to finish.
     */
    public static CCMConfig defaults() {
        return new CCMConfig(ProtocolType.LOCK_BASED, 5000L);
    }

    public CCMConfig withRollbackRetry(int attempts, long delayMs) {
        return new CCMConfig(protocol, deadlockTimeoutMs, attempts, delayMs, applyDrainTimeoutMs);
    }

    public CCMConfig withApplyDrainTimeoutMs(long timeoutMs) {
        return new CCMConfig(protocol, deadlockTimeoutMs, rollbackAttempts, rollbackRetryDelayMs, timeoutMs);
    }
}
