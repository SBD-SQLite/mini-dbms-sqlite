package sqlike.frm.maintenance;

import java.time.Instant;
import java.util.Objects;

/** Record data kegagalan eksekusi checkpoint periodik terakhir. */
public record PeriodicFailure(Instant failedAt, String reason, Throwable cause) {
    public PeriodicFailure {
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(reason, "reason");
    }
}