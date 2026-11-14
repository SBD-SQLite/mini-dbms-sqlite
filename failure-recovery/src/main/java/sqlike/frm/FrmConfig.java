package sqlike.frm;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * FRM settings chosen by the bootstrap (PRD v1.0 §15).
 *
 * @param logPath            active WAL file
 * @param checkpointInterval delay between periodic checkpoints; zero disables the timer, which is
 *                           only meant for specific tests, not for the normal demo configuration
 * @param idleTimeout        how long a checkpoint or PITR waits for active transactions to finish
 *                           before it is cancelled
 */
public record FrmConfig(Path logPath, Duration checkpointInterval, Duration idleTimeout) {

    /** PRD default: periodic checkpoint every 60 seconds. */
    public static final Duration DEFAULT_CHECKPOINT_INTERVAL = Duration.ofSeconds(60);

    /** PRD default: wait at most 30 seconds for active transactions. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

    public FrmConfig {
        Objects.requireNonNull(logPath, "logPath");
        Objects.requireNonNull(checkpointInterval, "checkpointInterval");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (checkpointInterval.isNegative()) {
            throw new IllegalArgumentException("checkpointInterval must not be negative");
        }
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
    }

    /** Normal configuration: periodic checkpoint every 60 s, idle timeout 30 s. */
    public static FrmConfig defaults(Path logPath) {
        return new FrmConfig(logPath, DEFAULT_CHECKPOINT_INTERVAL, DEFAULT_IDLE_TIMEOUT);
    }

    public FrmConfig withCheckpointInterval(Duration interval) {
        return new FrmConfig(logPath, interval, idleTimeout);
    }

    public FrmConfig withIdleTimeout(Duration timeout) {
        return new FrmConfig(logPath, checkpointInterval, timeout);
    }
}
