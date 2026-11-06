package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryResult;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.ResourceId;
import sqlike.shared.SchemaChange;
import sqlike.shared.Value;

/**
 * In-memory mock implementation of {@link IFailureRecoveryManager} for isolated CCM testing (CC-10).
 *
 * <p>Supports WAL failure injection, per-transaction change tracking, and simulated undo on
 * {@link #rollback(long)} so scenarios can verify that aborted writes restore old values (before-image).
 */
public class MockFRM implements IFailureRecoveryManager {

    private final List<String> log = new ArrayList<>();
    private final AtomicLong lsnGenerator = new AtomicLong(0);
    private final Map<Long, List<DataChange>> txChanges = new HashMap<>();
    private final Map<String, RecordImage> simulatedStore = new HashMap<>();
    private final Set<Long> committedTransactions = new HashSet<>();
    private final Set<Long> abortedTransactions = new HashSet<>();
    private final Set<Long> rolledBackTransactions = new HashSet<>();
    private boolean failNext = false;
    private String failOnOperation = null;

    public synchronized void setFailNext(boolean failNext) {
        this.failNext = failNext;
    }

    public synchronized void setFailOnOperation(String operation) {
        this.failOnOperation = operation;
    }

    public synchronized List<String> getLog() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    public synchronized void clear() {
        log.clear();
        lsnGenerator.set(0);
        txChanges.clear();
        simulatedStore.clear();
        committedTransactions.clear();
        abortedTransactions.clear();
        rolledBackTransactions.clear();
        failNext = false;
        failOnOperation = null;
    }

    public synchronized void seedValue(ResourceId resource, int value) {
        RecordImage image = new RecordImage(true, List.of(
                new Value(DataType.INTEGER, Optional.of(value), Optional.empty(), Optional.empty())));
        simulatedStore.put(resourceKey(resource), image);
    }

    public synchronized Optional<Integer> readIntValue(ResourceId resource) {
        RecordImage img = simulatedStore.get(resourceKey(resource));
        if (img == null || !img.exists() || img.values().isEmpty()) {
            return Optional.empty();
        }
        return img.values().get(0).integerValue();
    }

    public synchronized Optional<RecordImage> readRecordImage(ResourceId resource) {
        return Optional.ofNullable(simulatedStore.get(resourceKey(resource)));
    }

    public synchronized boolean wasRolledBack(long txId) {
        return rolledBackTransactions.contains(txId);
    }

    public synchronized boolean wasAborted(long txId) {
        return abortedTransactions.contains(txId);
    }

    public synchronized boolean wasCommitted(long txId) {
        return committedTransactions.contains(txId);
    }

    private static String resourceKey(ResourceId resource) {
        RID rid = resource.rid().orElseGet(() -> new RID(new PageId(resource.tableName(), 0L), 0));
        return resource.tableName() + "#" + rid.pageId().pageNumber() + ":" + rid.slotId();
    }

    private static String changeKey(DataChange change) {
        RID rid = change.rid();
        return change.tableName() + "#" + rid.pageId().pageNumber() + ":" + rid.slotId();
    }

    private void checkFailNext(String operation) {
        if (failNext || (failOnOperation != null && failOnOperation.equalsIgnoreCase(operation))) {
            failNext = false;
            failOnOperation = null;
            throw new RuntimeException("MockFRM simulated WAL failure on " + operation);
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
        txChanges.computeIfAbsent(txId, k -> new ArrayList<>()).add(change);
        if (change.after() != null) {
            simulatedStore.put(changeKey(change), change.after());
        }
        return lsn;
    }

    @Override
    public synchronized long logSchemaChange(long txId, SchemaChange change) {
        checkFailNext("logSchemaChange");
        long lsn = lsnGenerator.incrementAndGet();
        log.add(String.format("LSN=%d SCHEMA_CHANGE tx=%d tables=%d",
                lsn, txId, change == null ? 0 : change.after().size()));
        return lsn;
    }

    @Override
    public synchronized void logCommit(long txId) {
        checkFailNext("logCommit");
        long lsn = lsnGenerator.incrementAndGet();
        committedTransactions.add(txId);
        log.add(String.format("LSN=%d COMMIT tx=%d", lsn, txId));
    }

    @Override
    public synchronized void logAbort(long txId) {
        checkFailNext("logAbort");
        long lsn = lsnGenerator.incrementAndGet();
        abortedTransactions.add(txId);
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
        if (rolledBackTransactions.add(txId)) {
            List<DataChange> changes = txChanges.getOrDefault(txId, Collections.emptyList());
            for (int i = changes.size() - 1; i >= 0; i--) {
                DataChange change = changes.get(i);
                if (change.before() != null) {
                    simulatedStore.put(changeKey(change), change.before());
                }
            }
        }
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
