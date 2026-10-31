package sqlike.qp.stub;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;

/** STUB ONLY: CCM/FRM fake with scripted access, event traces, and overlay rollback; no locks or WAL. */
public final class StubTransactions implements IConcurrencyControl {
    private final StubStorage storage;
    private final List<String> trace;
    private final AtomicLong ids;
    private final Map<TxId, TransactionState> states = new ConcurrentHashMap<>();
    private final Map<TxId, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final Map<ChangeId, WritePermit> permits = new ConcurrentHashMap<>();
    private final Set<ChangeId> leases = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AccessGrant> waits = new ConcurrentHashMap<>();
    private final Queue<AccessStatus> script = new ConcurrentLinkedQueue<>();
    private final AtomicLong lsn = new AtomicLong();
    public CountDownLatch enteredWait = new CountDownLatch(1), releaseWait = new CountDownLatch(0);
    public TransactionState nextCommit;
    public boolean failLog, failConfirm, failAbort;
    public StubTransactions(StubStorage storage, List<String> trace) { this.storage = storage; this.trace = trace; ids = new AtomicLong(storage.lastTxId()); }
    public void scriptAccess(AccessStatus... statuses) { script.addAll(List.of(statuses)); }
    @Override public void initialize(RecoveryReport report) {
        if (!report.success()) throw failure("Recovery failed");
        states.clear(); tokens.clear(); permits.clear(); leases.clear(); waits.clear(); ids.set(Math.max(storage.lastTxId(), report.lastTxId()));
    }
    private void log(String event) {
        if (failLog) { failLog = false; throw failure("Simulated FRM log failure"); } trace.add(event);
    }
    @Override public TxId begin(SessionId sessionId) {
        TxId tx = new TxId(ids.incrementAndGet()); storage.recordTx(tx.value()); log("FRM.BEGIN." + tx.value());
        states.put(tx, TransactionState.ACTIVE); tokens.put(tx, new CancellationToken()); return tx;
    }
    private void active(TxId tx) { if (getState(tx) != TransactionState.ACTIVE) throw failure("Transaction not active"); }
    @Override public AccessDecision acquireAccess(TxId tx, ResourceKey resource, AccessMode mode) {
        active(tx); trace.add("CCM.access." + mode); return decide(UUID.randomUUID(), new AccessGrant(tx, resource, mode));
    }
    private AccessDecision decide(UUID request, AccessGrant grant) {
        AccessStatus status = Optional.ofNullable(script.poll()).orElse(AccessStatus.GRANTED);
        if (status == AccessStatus.WAIT) waits.put(request, grant);
        if (status == AccessStatus.ABORTED) abort(grant.txId(), "Scripted denial");
        return new AccessDecision(status, status == AccessStatus.GRANTED ? Optional.of(grant) : Optional.empty(), request);
    }
    @Override public AccessDecision waitAccess(UUID requestId) {
        trace.add("CCM.wait"); enteredWait.countDown();
        try { if (!releaseWait.await(5, TimeUnit.SECONDS)) throw failure("Stub wait timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure("Access wait interrupted"); }
        AccessGrant grant = Optional.ofNullable(waits.remove(requestId)).orElseThrow(() -> failure("Unknown wait request"));
        return getState(grant.txId()) == TransactionState.ACTIVE ? decide(requestId, grant) : new AccessDecision(AccessStatus.ABORTED, Optional.empty(), requestId);
    }
    @Override public WritePermit authorizeChange(ChangeRecord change) {
        active(change.txId()); log("FRM.CHANGE"); WritePermit permit = new WritePermit(change.id(), new Lsn(lsn.incrementAndGet()));
        permits.put(change.id(), permit); return permit;
    }
    @Override public WriteLease acquireApplyLease(WritePermit permit) {
        active(permit.changeId().txId());
        if (!permit.equals(permits.get(permit.changeId())) || !leases.add(permit.changeId())) throw failure("Invalid apply lease");
        trace.add("CCM.lease"); return () -> { leases.remove(permit.changeId()); trace.add("CCM.lease.close"); };
    }
    @Override public void confirmApplied(ChangeId id) {
        if (!leases.contains(id)) throw failure("Missing apply lease");
        if (failConfirm) { failConfirm = false; throw failure("Simulated confirm failure"); }
        permits.remove(id); trace.add("CCM.confirm");
    }
    @Override public TransactionState getState(TxId tx) { return states.getOrDefault(tx, TransactionState.FAILED); }
    @Override public CancellationToken getCancellationToken(TxId tx) { return Objects.requireNonNull(tokens.get(tx)); }
    @Override public TransactionState commit(TxId tx) {
        if (getState(tx) != TransactionState.ACTIVE) return getState(tx);
        TransactionState scripted = nextCommit; nextCommit = null; trace.add("CCM.commit");
        if (scripted == TransactionState.ABORTED) return abort(tx, "Commit rejected");
        if (scripted != null && scripted != TransactionState.COMMITTED) { states.put(tx, scripted); return scripted; }
        if (permits.keySet().stream().anyMatch(id -> id.txId().equals(tx))) { states.put(tx, TransactionState.FAILED); return TransactionState.FAILED; }
        states.put(tx, TransactionState.PARTIALLY_COMMITTED);
        try { log("FRM.prepareCommit"); storage.commit(tx); log("FRM.COMMIT." + tx.value()); states.put(tx, TransactionState.COMMITTED); }
        catch (RuntimeException failure) { states.put(tx, TransactionState.FAILED); }
        return getState(tx);
    }
    @Override public TransactionState abort(TxId tx, String reason) {
        if (getState(tx) == TransactionState.COMMITTED || getState(tx) == TransactionState.ABORTED) return getState(tx);
        if (failAbort) { failAbort = false; states.put(tx, TransactionState.FAILED); return TransactionState.FAILED; }
        trace.add("FRM.rollback"); storage.discard(tx); permits.keySet().removeIf(id -> id.txId().equals(tx));
        leases.removeIf(id -> id.txId().equals(tx)); states.put(tx, TransactionState.ABORTED); getCancellationToken(tx).cancel();
        trace.add("FRM.ABORT." + tx.value()); return TransactionState.ABORTED;
    }
}
