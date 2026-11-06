package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.shared.DataChange;
import sqlike.shared.RecoveryResult;
import sqlike.shared.RecoveryTarget;

/**
 * In-memory mock implementation of {@link IFailureRecoveryManager} for isolated CCM testing (CC-10).
 */
public class MockFRM implements IFailureRecoveryManager {

    private final List<String> log = new ArrayList<>();
    private final AtomicLong lsnGenerator = new AtomicLong(0);
    private boolean failNext = false;

    public synchronized void setFailNext(boolean failNext) {
        this.failNext = failNext;
    }

    public synchronized List<String> getLog() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    public synchronized void clear() {
        log.clear();
        lsnGenerator.set(0);
        failNext = false;
    }

    private void checkFailNext(String operation) {
        if (failNext) {
            failNext = false;
            throw new RuntimeException("MockFRM simulated failure on " + operation);
        }
    }

    @Override
    public synchronized long logBegin(long txId) {
        checkFailNext("logBegin");
        long lsn = lsnGenerator.incrementAndGet();
        log.add(String.format("LSN=%d BEGIN tx=%d", lsn, txId));
        return lsn;
    }

    @Override
    public synchronized long logChange(long txId, DataChange change) {
        checkFailNext("logChange");
        long lsn = lsnGenerator.incrementAndGet();
        log.add(String.format("LSN=%d CHANGE tx=%d op=%s table=%s rid=%s",
                lsn, txId, change.operation(), change.tableName(), change.rid()));
        return lsn;
    }

    @Override
    public synchronized void logCommit(long txId) {
        checkFailNext("logCommit");
        long lsn = lsnGenerator.incrementAndGet();
        log.add(String.format("LSN=%d COMMIT tx=%d", lsn, txId));
    }

    @Override
    public synchronized void logAbort(long txId) {
        checkFailNext("logAbort");
        long lsn = lsnGenerator.incrementAndGet();
        log.add(String.format("LSN=%d ABORT tx=%d", lsn, txId));
    }

    @Override
    public synchronized void ensureDurable(long lsn) {
        checkFailNext("ensureDurable");
        log.add(String.format("DURABLE upToLSN=%d", lsn));
    }

    @Override
    public synchronized void rollback(long txId) {
        checkFailNext("rollback");
        log.add(String.format("ROLLBACK tx=%d", txId));
    }

    @Override
    public synchronized RecoveryResult recover() {
        checkFailNext("recover");
        long lsn = lsnGenerator.get();
        log.add("RECOVER");
        return new RecoveryResult(0L, 0L, lsn, Optional.empty());
    }

    @Override
    public synchronized void checkpoint() {
        checkFailNext("checkpoint");
        long lsn = lsnGenerator.incrementAndGet();
        log.add(String.format("LSN=%d CHECKPOINT", lsn));
    }

    @Override
    public synchronized void recoverTo(RecoveryTarget target) {
        checkFailNext("recoverTo");
        log.add(String.format("RECOVER_TO kind=%s", target.kind()));
    }
}
