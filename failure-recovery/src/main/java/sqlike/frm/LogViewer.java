package sqlike.frm;

import java.util.List;
import java.util.Optional;

import sqlike.frm.log.LogRecord;

/**
 * Read-only view of the WAL for drivers and diagnostics (FR-03). Reading never changes the log.
 */
public interface LogViewer {

    /** Valid log records in ascending LSN order; only {@code txId}'s records when present. */
    List<LogRecord> readLogs(Optional<Long> txId);

    /** Human-readable form of {@link #readLogs(Optional)}. */
    String showLogs(Optional<Long> txId);
}
