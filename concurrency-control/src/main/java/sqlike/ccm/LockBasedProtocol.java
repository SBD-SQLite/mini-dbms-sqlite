package sqlike.ccm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

import sqlike.shared.AccessStatus;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;
import sqlike.shared.ResourceScope;
import sqlike.shared.TransactionStatus;

/**
 * Table-level rigorous 2PL with FIFO waiting and shared-to-exclusive upgrades.
 * Locks stay held until commit or rollback finishes.
 * Supply an abort callback when using deadlock resolution outside the CCM facade.
 */
public class LockBasedProtocol implements IConcurrencyProtocol {

    private static final long DEADLOCK_CHECK_MS = 100L;

    private final ReentrantLock mutex = new ReentrantLock();
    private final Condition changed = mutex.newCondition();
    private final Map<ResourceId, LockEntry> lockTable = new HashMap<>();
    private final Map<Long, Transaction> transactions = new HashMap<>();
    private final Set<Long> aborting = new HashSet<>();
    private final DeadlockHandler deadlockHandler;
    private final LongConsumer abortHandler;
    private long generation;

    public LockBasedProtocol() {
        this(new DeadlockHandler());
    }

    public LockBasedProtocol(DeadlockHandler deadlockHandler) {
        this(deadlockHandler, null);
    }

    /** The callback must finish rollback and call release, or throw while retaining the locks. */
    public LockBasedProtocol(DeadlockHandler deadlockHandler, LongConsumer abortHandler) {
        this.deadlockHandler = Objects.requireNonNull(deadlockHandler, "deadlockHandler");
        this.abortHandler = abortHandler;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.LOCK_BASED;
    }

    @Override
    public void onBegin(Transaction tx) {
        Objects.requireNonNull(tx, "tx");
        mutex.lock();
        try {
            if (!tx.isActive()) {
                throw new IllegalStateException("Only active transactions can begin a protocol");
            }
            register(tx);
        } finally {
            mutex.unlock();
        }
    }

    @Override
    public AccessStatus checkRead(Transaction tx, ResourceId resource) {
        return acquire(tx, resource, LockMode.SHARED);
    }

    @Override
    public AccessStatus checkWrite(Transaction tx, ResourceId resource) {
        return acquire(tx, resource, LockMode.EXCLUSIVE);
    }

    @Override
    public void release(Transaction tx) {
        Objects.requireNonNull(tx, "tx");
        mutex.lock();
        try {
            if (!isFinished(tx)) {
                throw new IllegalStateException("Locks must stay held until commit or rollback finishes");
            }
            if (transactions.get(tx.getTxId()) != tx) {
                return;
            }
            Iterator<LockEntry> entries = lockTable.values().iterator();
            while (entries.hasNext()) {
                LockEntry entry = entries.next();
                entry.holders.remove(tx.getTxId());
                entry.waiters.removeIf(request -> request.tx == tx);
                if (entry.isEmpty()) {
                    entries.remove();
                }
            }
            transactions.remove(tx.getTxId());
            aborting.remove(tx.getTxId());
            updateGraph();
            changed.signalAll();
        } finally {
            mutex.unlock();
        }
    }

    @Override
    public void reset() {
        mutex.lock();
        try {
            generation++;
            lockTable.clear();
            transactions.clear();
            aborting.clear();
            deadlockHandler.reset();
            changed.signalAll();
        } finally {
            mutex.unlock();
        }
    }

    /** A snapshot for the component driver to observe requests that are waiting. */
    public Set<Long> getWaitingTransactions() {
        mutex.lock();
        try {
            Set<Long> waiting = new HashSet<>();
            for (LockEntry entry : lockTable.values()) {
                for (LockRequest request : entry.waiters) {
                    waiting.add(request.tx.getTxId());
                }
            }
            return Set.copyOf(waiting);
        } finally {
            mutex.unlock();
        }
    }

    private AccessStatus acquire(Transaction tx, ResourceId resource, LockMode mode) {
        Objects.requireNonNull(tx, "tx");
        ResourceId key = canonicalResource(resource);
        boolean interrupted = false;
        LockEntry entry = null;
        LockRequest request = null;
        mutex.lock();
        try {
            if (!tx.isActive()) {
                return AccessStatus.ABORTED;
            }
            register(tx);
            long requestGeneration = generation;
            entry = lockTable.computeIfAbsent(key, ignored -> new LockEntry());
            request = new LockRequest(tx, mode);
            entry.waiters.addLast(request);

            while (requestGeneration == generation) {
                if (isFinished(tx)) {
                    return AccessStatus.ABORTED;
                }
                if (tx.isActive() && !aborting.contains(tx.getTxId())) {
                    LockMode held = entry.holders.get(tx.getTxId());
                    if (held == LockMode.EXCLUSIVE || held == mode || blockers(entry, request).isEmpty()) {
                        entry.holders.put(tx.getTxId(), held == LockMode.EXCLUSIVE ? held : mode);
                        return AccessStatus.GRANTED;
                    }
                    updateGraph();
                    List<Long> cycle = deadlockHandler.detectCycle();
                    if (!cycle.isEmpty()) {
                        requestAbort(deadlockHandler.chooseVictim(cycle));
                        continue;
                    }
                } else {
                    entry.waiters.remove(request);
                    updateGraph();
                }

                try {
                    changed.await(DEADLOCK_CHECK_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (tx.isActive() && !aborting.contains(tx.getTxId())) {
                        if (abortHandler == null) {
                            return AccessStatus.ABORTED;
                        }
                        requestAbort(tx.getTxId());
                    }
                }
            }
            return AccessStatus.ABORTED;
        } finally {
            if (request != null) {
                entry.waiters.remove(request);
                if (entry.isEmpty()) {
                    lockTable.remove(key, entry);
                }
                updateGraph();
                changed.signalAll();
            }
            mutex.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register(Transaction tx) {
        Transaction existing = transactions.putIfAbsent(tx.getTxId(), tx);
        if (existing != null && existing != tx) {
            throw new IllegalArgumentException("Transaction ID is already registered: " + tx.getTxId());
        }
    }

    private Set<Long> blockers(LockEntry entry, LockRequest request) {
        Set<Long> blockers = new HashSet<>();
        long txId = request.tx.getTxId();
        entry.holders.forEach((holderId, held) -> {
            if (holderId != txId && (held == LockMode.EXCLUSIVE || request.mode == LockMode.EXCLUSIVE)) {
                blockers.add(holderId);
            }
        });
        // Earlier requests also block access so new readers cannot pass a waiting writer.
        for (LockRequest earlier : entry.waiters) {
            if (earlier == request) {
                break;
            }
            if (earlier.tx.getTxId() != txId) {
                blockers.add(earlier.tx.getTxId());
            }
        }
        return blockers;
    }

    private void updateGraph() {
        Map<Long, Set<Long>> graph = new HashMap<>();
        for (LockEntry entry : lockTable.values()) {
            for (LockRequest request : entry.waiters) {
                if (request.tx.isActive() && !aborting.contains(request.tx.getTxId())) {
                    graph.computeIfAbsent(request.tx.getTxId(), ignored -> new HashSet<>())
                            .addAll(blockers(entry, request));
                }
            }
        }
        deadlockHandler.updateWaitFor(graph);
    }

    private void requestAbort(long txId) {
        if (abortHandler == null) {
            throw new IllegalStateException("Configure an abort handler to resolve deadlocks");
        }
        if (!aborting.add(txId)) {
            return;
        }
        for (LockEntry entry : lockTable.values()) {
            entry.waiters.removeIf(request -> request.tx.getTxId() == txId);
        }
        updateGraph();
        changed.signalAll();

        // Rollback may perform I/O. Other transactions must still be able to use the lock table.
        long abortGeneration = generation;
        mutex.unlock();
        try {
            abortHandler.accept(txId);
        } finally {
            mutex.lock();
        }
        if (abortGeneration == generation && transactions.containsKey(txId)) {
            throw new IllegalStateException("Abort handler returned before releasing transaction " + txId);
        }
    }

    private static boolean isFinished(Transaction tx) {
        return tx.getStatus() == TransactionStatus.COMMITTED || tx.getStatus() == TransactionStatus.ABORTED;
    }

    private static ResourceId canonicalResource(ResourceId resource) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(resource.scope(), "resource.scope");
        String name = Objects.requireNonNull(resource.tableName(), "resource.tableName")
                .toLowerCase(Locale.ROOT);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Resource name must not be blank");
        }
        if (resource.scope() == ResourceScope.CATALOG) {
            if (!name.equals("__catalog__") || resource.rid().isPresent()) {
                throw new IllegalArgumentException("Catalog resources must use __catalog__ without a RID");
            }
            return new ResourceId(ResourceScope.CATALOG, name, Optional.empty());
        }
        if (name.equals("__catalog__")) {
            throw new IllegalArgumentException("__catalog__ is reserved for catalog resources");
        }
        if ((resource.scope() == ResourceScope.RECORD) != resource.rid().isPresent()) {
            throw new IllegalArgumentException("Only record resources must carry a RID");
        }
        return new ResourceId(ResourceScope.TABLE, name, Optional.empty());
    }

    private enum LockMode {
        SHARED,
        EXCLUSIVE
    }

    private static final class LockEntry {
        final Map<Long, LockMode> holders = new LinkedHashMap<>();
        final Deque<LockRequest> waiters = new ArrayDeque<>();

        boolean isEmpty() {
            return holders.isEmpty() && waiters.isEmpty();
        }
    }

    private static final class LockRequest {
        final Transaction tx;
        final LockMode mode;

        LockRequest(Transaction tx, LockMode mode) {
            this.tx = tx;
            this.mode = mode;
        }
    }
}
