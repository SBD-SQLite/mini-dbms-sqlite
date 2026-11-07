package sqlike.ccm;

import java.util.Objects;
import java.util.function.Supplier;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.shared.DataChange;
import sqlike.shared.SchemaChange;

/**
 * Forwards transaction events, data changes, and rollbacks to FRM following WAL (CC3).
 *
 * <p>Any {@link RuntimeException} from FRM becomes a {@link FrmFailureException}. Rollback and
 * logAbort are retried a limited number of times because both are safe to repeat.
 */
public class FRMBridge {

    private final IFailureRecoveryManager recovery;
    private final int attempts;
    private final long retryDelayMs;

    public FRMBridge(IFailureRecoveryManager recovery) {
        this(recovery, CCMConfig.defaults());
    }

    public FRMBridge(IFailureRecoveryManager recovery, CCMConfig config) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(config, "config");
        this.attempts = config.rollbackAttempts();
        this.retryDelayMs = config.rollbackRetryDelayMs();
    }

    /** Logs BEGIN for the transaction (CC-03). */
    public void logBegin(long txId) {
        call("logBegin", () -> recovery.logBegin(txId));
    }

    /** Logs one granted write and returns its change LSN; must succeed before the change is applied (CC-03). */
    public long logWrite(long txId, DataChange change) {
        return positiveLsn("logChange", call("logChange", () -> recovery.logChange(txId, change)));
    }

    /** Logs one granted schema change and returns its LSN. */
    public long logSchema(long txId, SchemaChange change) {
        return positiveLsn("logSchemaChange", call("logSchemaChange", () -> recovery.logSchemaChange(txId, change)));
    }

    /** Returns only after the COMMIT record is durable (CC-03). */
    public void logCommit(long txId) {
        call("logCommit", () -> {
            recovery.logCommit(txId);
            return null;
        });
    }

    /** Undoes the transaction, then records that the abort finished (CC-04). */
    public void rollbackThenLogAbort(long txId) {
        retry("rollback", () -> recovery.rollback(txId));
        retry("logAbort", () -> recovery.logAbort(txId));
    }

    private <T> T call(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            throw new FrmFailureException(operation, e);
        }
    }

    private void call(String operation, Runnable action) {
        call(operation, () -> {
            action.run();
            return null;
        });
    }

    private static long positiveLsn(String operation, long lsn) {
        if (lsn <= 0) {
            throw new FrmFailureException(operation, "returned a non-positive LSN " + lsn);
        }
        return lsn;
    }

    private void retry(String operation, Runnable action) {
        FrmFailureException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                call(operation, action);
                return;
            } catch (FrmFailureException e) {
                last = e;
                if (attempt < attempts && !sleep()) {
                    break;
                }
            }
        }
        throw last;
    }

    private boolean sleep() {
        try {
            Thread.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
