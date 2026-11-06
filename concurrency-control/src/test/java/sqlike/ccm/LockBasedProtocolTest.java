package sqlike.ccm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.ResourceId;
import sqlike.shared.ResourceScope;
import sqlike.shared.TransactionStatus;
import sqlike.shared.Value;

/** Standalone CC2 checks using real concurrent requests and controlled rollback callbacks. */
public final class LockBasedProtocolTest {

    private static int passed;

    public static void main(String[] args) throws Exception {
        test("acyclic and cyclic wait-for graphs", LockBasedProtocolTest::graphs);
        test("shared readers and canonical table resources", LockBasedProtocolTest::sharedReaders);
        test("record writes wait for table scans", LockBasedProtocolTest::scanBlocksWrite);
        test("different tables remain independent", LockBasedProtocolTest::independentTables);
        test("repeated locks need only one release", LockBasedProtocolTest::repeatedLocks);
        test("FIFO prevents readers passing a waiting writer", LockBasedProtocolTest::fifo);
        test("uncontested shared-to-exclusive upgrade", LockBasedProtocolTest::upgrade);
        test("two-transaction deadlock", LockBasedProtocolTest::twoTransactions);
        test("three-transaction deadlock", LockBasedProtocolTest::threeTransactions);
        test("competing upgrades form a deadlock", LockBasedProtocolTest::upgradeDeadlock);
        test("FIFO upgrade cycles include queued writers", LockBasedProtocolTest::queuedUpgrade);
        test("concurrent writes preserve the final data", LockBasedProtocolTest::concurrentWrites);
        test("waiting without a cycle does not abort", LockBasedProtocolTest::noFalseVictim);
        test("rollback keeps locks without blocking unrelated tables", LockBasedProtocolTest::rollbackProtection);
        test("failed rollback retains protection", LockBasedProtocolTest::failedRollback);
        test("external abort cancels a pending request", LockBasedProtocolTest::externalAbort);
        test("reset cancels old waiters", LockBasedProtocolTest::reset);
        test("interrupted requests use the abort callback", LockBasedProtocolTest::interruption);
        test("catalog locks use a separate resource", LockBasedProtocolTest::catalog);
        test("deadlock victims pass through the CCM facade", LockBasedProtocolTest::facade);
        System.out.println("CC2: " + passed + "/20 tests passed");
    }

    private static void graphs() {
        DeadlockHandler graph = new DeadlockHandler();
        graph.updateWaitFor(Map.of(1L, Set.of(2L), 2L, Set.of(3L)));
        require(graph.detectCycle().isEmpty(), "a chain is not a deadlock");
        graph.updateWaitFor(Map.of(1L, Set.of(2L), 2L, Set.of(1L)));
        require(Set.copyOf(graph.detectCycle()).equals(Set.of(1L, 2L)), "two-node cycle missing");
        graph.updateWaitFor(Map.of(1L, Set.of(2L), 2L, Set.of(3L), 3L, Set.of(1L)));
        List<Long> cycle = graph.detectCycle();
        require(Set.copyOf(cycle).equals(Set.of(1L, 2L, 3L)), "three-node cycle missing");
        require(graph.chooseVictim(cycle) == 3, "the youngest transaction must be the victim");
        Map<Long, Set<Long>> snapshot = graph.getWaitForGraph();
        graph.reset();
        require(graph.detectCycle().isEmpty() && snapshot.size() == 3, "graph snapshots must be independent");
        try {
            graph.chooseVictim(List.of());
            throw new AssertionError("an empty cycle cannot have a victim");
        } catch (IllegalArgumentException expected) {
            // No transaction should be selected from an empty cycle.
        }
    }

    private static void sharedReaders() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction first = f.begin(1);
            Transaction second = f.begin(2);
            granted(f.protocol.checkRead(first, ResourceId.ofTable("Accounts")));
            granted(get(f.submit(() -> f.protocol.checkRead(second, record("accounts", 1)))));
            require(f.protocol.getWaitingTransactions().isEmpty(), "shared readers should not wait");
            f.commit(first);
            f.commit(second);
        }
    }

    private static void scanBlocksWrite() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction reader = f.begin(1);
            Transaction writer = f.begin(2);
            granted(f.protocol.checkRead(reader, table("accounts")));
            Future<AccessStatus> pending = f.submit(() -> f.protocol.checkWrite(writer, record("accounts", 9)));
            f.waiting(writer);
            require(!pending.isDone(), "a record write must wait for a table scan");
            f.commit(reader);
            granted(get(pending));
            f.commit(writer);
        }
    }

    private static void independentTables() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction first = f.begin(1);
            Transaction second = f.begin(2);
            Transaction other = f.begin(3);
            granted(f.protocol.checkWrite(first, table("a")));
            Future<AccessStatus> pending = f.submit(() -> f.protocol.checkWrite(second, record("a", 4)));
            f.waiting(second);
            granted(get(f.submit(() -> f.protocol.checkWrite(other, table("b")))));
            require(!pending.isDone(), "the conflicting writer must still wait");
            f.commit(other);
            f.commit(first);
            granted(get(pending));
            f.commit(second);
        }
    }

    private static void repeatedLocks() throws Exception {
        for (boolean exclusive : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                Transaction holder = f.begin(1);
                Transaction next = f.begin(2);
                if (exclusive) {
                    granted(f.protocol.checkWrite(holder, table("a")));
                } else {
                    granted(f.protocol.checkRead(holder, table("a")));
                }
                Future<AccessStatus> pending = f.submit(() -> f.protocol.checkWrite(next, table("a")));
                f.waiting(next);
                granted(f.protocol.checkRead(holder, record("a", 1)));
                if (exclusive) {
                    granted(f.protocol.checkWrite(holder, record("a", 2)));
                }
                f.commit(holder);
                granted(get(pending));
                f.commit(next);
                require(f.graph.getWaitForGraph().isEmpty(), "finished requests left stale graph edges");
            }
        }
    }

    private static void fifo() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction holder = f.begin(1);
            Transaction writer = f.begin(2);
            Transaction reader = f.begin(3);
            granted(f.protocol.checkRead(holder, table("a")));
            Future<AccessStatus> write = f.submit(() -> f.protocol.checkWrite(writer, table("a")));
            f.waiting(writer);
            Future<AccessStatus> read = f.submit(() -> f.protocol.checkRead(reader, table("a")));
            f.waiting(reader);
            f.commit(holder);
            granted(get(write));
            require(!read.isDone(), "a reader passed the earlier writer");
            f.commit(writer);
            granted(get(read));
            f.commit(reader);
        }
    }

    private static void upgrade() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction holder = f.begin(1);
            Transaction reader = f.begin(2);
            granted(f.protocol.checkRead(holder, table("a")));
            granted(f.protocol.checkWrite(holder, record("a", 1)));
            Future<AccessStatus> pending = f.submit(() -> f.protocol.checkRead(reader, table("a")));
            f.waiting(reader);
            f.commit(holder);
            granted(get(pending));
            f.commit(reader);
        }
    }

    private static void twoTransactions() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction first = f.begin(1);
            Transaction second = f.begin(2);
            granted(f.protocol.checkWrite(first, table("a")));
            granted(f.protocol.checkWrite(second, table("b")));
            long start = System.nanoTime();
            Future<AccessStatus> one = f.submit(() -> f.protocol.checkWrite(first, table("b")));
            f.waiting(first);
            Future<AccessStatus> two = f.submit(() -> f.protocol.checkWrite(second, table("a")));
            require(get(two) == AccessStatus.ABORTED, "youngest transaction survived the cycle");
            granted(get(one));
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000, "deadlock took over five seconds");
            require(f.aborted.equals(List.of(2L)), "exactly one victim must be aborted");
            f.commit(first);
            require(f.graph.getWaitForGraph().isEmpty(), "deadlock edges were not removed");
        }
    }

    private static void threeTransactions() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction first = f.begin(1);
            Transaction second = f.begin(2);
            Transaction third = f.begin(3);
            granted(f.protocol.checkWrite(first, table("a")));
            granted(f.protocol.checkWrite(second, table("b")));
            granted(f.protocol.checkWrite(third, table("c")));
            Future<AccessStatus> one = f.submit(() -> f.protocol.checkWrite(first, table("b")));
            f.waiting(first);
            Future<AccessStatus> two = f.submit(() -> f.protocol.checkWrite(second, table("c")));
            f.waiting(second);
            Future<AccessStatus> three = f.submit(() -> f.protocol.checkWrite(third, table("a")));
            require(get(three) == AccessStatus.ABORTED, "wrong victim in the three-way cycle");
            granted(get(two));
            f.commit(second);
            granted(get(one));
            f.commit(first);
            require(f.aborted.equals(List.of(3L)), "three-way cycle aborted extra transactions");
        }
    }

    private static void upgradeDeadlock() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction first = f.begin(1);
            Transaction second = f.begin(2);
            granted(f.protocol.checkRead(first, table("a")));
            granted(f.protocol.checkRead(second, table("a")));
            Future<AccessStatus> one = f.submit(() -> f.protocol.checkWrite(first, table("a")));
            f.waiting(first);
            Future<AccessStatus> two = f.submit(() -> f.protocol.checkWrite(second, table("a")));
            require(get(two) == AccessStatus.ABORTED, "upgrade deadlock was not resolved");
            granted(get(one));
            f.commit(first);
            require(f.aborted.equals(List.of(2L)), "wrong upgrade victim");
        }
    }

    private static void noFalseVictim() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction first = f.begin(1);
            Transaction second = f.begin(2);
            granted(f.protocol.checkWrite(first, table("a")));
            Future<AccessStatus> pending = f.submit(() -> f.protocol.checkRead(second, table("a")));
            f.waiting(second);
            try {
                pending.get(250, TimeUnit.MILLISECONDS);
                throw new AssertionError("request completed before its holder released the lock");
            } catch (TimeoutException expected) {
                require(f.aborted.isEmpty(), "waiting without a cycle caused an abort");
            }
            f.commit(first);
            granted(get(pending));
            f.commit(second);
        }
    }

    private static void queuedUpgrade() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction reader = f.begin(1);
            Transaction writer = f.begin(2);
            granted(f.protocol.checkRead(reader, table("a")));
            Future<AccessStatus> write = f.submit(() -> f.protocol.checkWrite(writer, table("a")));
            f.waiting(writer);
            Future<AccessStatus> upgrade = f.submit(() -> f.protocol.checkWrite(reader, table("a")));
            require(get(write) == AccessStatus.ABORTED, "queued writer was missing from the cycle");
            granted(get(upgrade));
            require(f.aborted.equals(List.of(2L)), "wrong victim in the FIFO upgrade cycle");
            f.commit(reader);
        }
    }

    private static void concurrentWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger value = new AtomicInteger();
            AtomicLong ids = new AtomicLong();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> writers = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                int slot = worker;
                writers.add(f.submit(() -> {
                    awaitLatch(start);
                    for (int i = 0; i < 50; i++) {
                        Transaction tx = f.begin(ids.incrementAndGet());
                        granted(f.protocol.checkWrite(tx, record("counter", slot)));
                        int before = value.get();
                        Thread.yield();
                        value.set(before + 1);
                        f.commit(tx);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> writer : writers) {
                get(writer);
            }
            require(value.get() == 200, "concurrent writes lost an update");
            require(f.aborted.isEmpty() && f.graph.getWaitForGraph().isEmpty(), "writers left protocol state");
        }
    }

    private static void rollbackProtection() throws Exception {
        CountDownLatch rollbackStarted = new CountDownLatch(1);
        CountDownLatch finishRollback = new CountDownLatch(1);
        AtomicInteger value = new AtomicInteger(100);
        try (Fixture f = new Fixture(tx -> {
            rollbackStarted.countDown();
            awaitLatch(finishRollback);
            value.set(100);
        })) {
            Transaction first = f.begin(1);
            Transaction victim = f.begin(2);
            Transaction reader = f.begin(3);
            Transaction unrelated = f.begin(4);
            granted(f.protocol.checkWrite(first, table("a")));
            granted(f.protocol.checkWrite(victim, table("b")));
            value.set(200);
            Future<AccessStatus> one = f.submit(() -> f.protocol.checkWrite(first, table("b")));
            f.waiting(first);
            Future<AccessStatus> two = f.submit(() -> f.protocol.checkWrite(victim, table("a")));
            awaitLatch(rollbackStarted);
            require(victim.getStatus() == TransactionStatus.FAILED, "victim should be rolling back");
            Future<AccessStatus> read = f.submit(() -> {
                AccessStatus status = f.protocol.checkRead(reader, table("b"));
                if (status == AccessStatus.GRANTED) {
                    require(value.get() == 100, "reader saw the victim's uncommitted value");
                }
                return status;
            });
            f.waiting(reader);
            require(!one.isDone() && !read.isDone(), "dirty data became accessible during rollback");
            try {
                f.protocol.release(victim);
                throw new AssertionError("FAILED transaction released its locks");
            } catch (IllegalStateException expected) {
                // Rollback has not finished yet.
            }
            granted(get(f.submit(() -> f.protocol.checkWrite(unrelated, table("c")))));
            f.commit(unrelated);
            finishRollback.countDown();
            require(get(two) == AccessStatus.ABORTED, "victim did not finish aborting");
            granted(get(one));
            f.commit(first);
            granted(get(read));
            f.commit(reader);
        } finally {
            finishRollback.countDown();
        }
    }

    private static void failedRollback() throws Exception {
        try (Fixture f = new Fixture(tx -> { throw new IllegalStateException("rollback failed"); })) {
            Transaction first = f.begin(1);
            Transaction victim = f.begin(2);
            Transaction reader = f.begin(3);
            granted(f.protocol.checkWrite(first, table("a")));
            granted(f.protocol.checkWrite(victim, table("b")));
            Future<AccessStatus> one = f.submit(() -> f.protocol.checkWrite(first, table("b")));
            f.waiting(first);
            Future<AccessStatus> two = f.submit(() -> f.protocol.checkWrite(victim, table("a")));
            try {
                get(two);
                throw new AssertionError("rollback failure was hidden");
            } catch (ExecutionException expected) {
                require(expected.getCause() instanceof IllegalStateException, "wrong rollback error");
            }
            Future<AccessStatus> read = f.submit(() -> f.protocol.checkRead(reader, table("b")));
            f.waiting(reader);
            require(victim.getStatus() == TransactionStatus.FAILED && !one.isDone() && !read.isDone(),
                    "failed rollback released dirty data");
            f.protocol.reset();
            require(get(one) == AccessStatus.ABORTED && get(read) == AccessStatus.ABORTED,
                    "reset must cancel requests from the failed run");
        }
    }

    private static void externalAbort() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction holder = f.begin(1);
            Transaction waiter = f.begin(2);
            granted(f.protocol.checkWrite(holder, table("a")));
            Future<AccessStatus> pending = f.submit(() -> f.protocol.checkRead(waiter, table("a")));
            f.waiting(waiter);
            f.abort(waiter.getTxId());
            require(get(pending) == AccessStatus.ABORTED, "aborted waiter acquired a lock");
            require(f.graph.getWaitForGraph().isEmpty(), "aborted waiter left graph edges");
            f.commit(holder);
        }
    }

    private static void reset() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction holder = f.begin(1);
            Transaction waiter = f.begin(2);
            granted(f.protocol.checkWrite(holder, table("a")));
            Future<AccessStatus> pending = f.submit(() -> f.protocol.checkWrite(waiter, table("a")));
            f.waiting(waiter);
            f.protocol.reset();
            require(get(pending) == AccessStatus.ABORTED, "old waiter survived a reset");
            Transaction fresh = f.begin(3);
            granted(get(f.submit(() -> f.protocol.checkWrite(fresh, table("a")))));
            f.commit(fresh);
            require(f.protocol.getWaitingTransactions().isEmpty(), "reset left pending requests");
        }
    }

    private static void interruption() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction holder = f.begin(1);
            Transaction waiter = f.begin(2);
            AtomicReference<Thread> worker = new AtomicReference<>();
            granted(f.protocol.checkWrite(holder, table("a")));
            Future<Boolean> pending = f.submit(() -> {
                worker.set(Thread.currentThread());
                AccessStatus result = f.protocol.checkRead(waiter, table("a"));
                return result == AccessStatus.ABORTED && Thread.currentThread().isInterrupted();
            });
            f.waiting(waiter);
            worker.get().interrupt();
            require(get(pending), "interrupt must abort the request and preserve the interrupt flag");
            require(f.aborted.equals(List.of(2L)), "interrupted transaction did not use the abort path");
            f.commit(holder);
        }
    }

    private static void catalog() throws Exception {
        try (Fixture f = new Fixture()) {
            Transaction ddl = f.begin(1);
            Transaction data = f.begin(2);
            Transaction binder = f.begin(3);
            ResourceId catalog = new ResourceId(ResourceScope.CATALOG, "__catalog__", Optional.empty());
            granted(f.protocol.checkWrite(ddl, catalog));
            granted(get(f.submit(() -> f.protocol.checkWrite(data, table("accounts")))));
            Future<AccessStatus> read = f.submit(() -> f.protocol.checkRead(binder, catalog));
            f.waiting(binder);
            f.commit(ddl);
            granted(get(read));
            f.commit(data);
            f.commit(binder);
        }
    }

    private static void facade() throws Exception {
        ConcurrencyControlManager ccm = new ConcurrencyControlManager(new MockFRM());
        ccm.initAfterRecovery(0);
        long first = ccm.beginTransaction().txId();
        long second = ccm.beginTransaction().txId();
        ResourceId a = record("a", 0);
        ResourceId b = record("b", 0);
        granted(ccm.requestWrite(first, change(a)).status());
        granted(ccm.requestWrite(second, change(b)).status());
        ExecutorService workers = workers();
        try {
            Future<AccessResponse> one = workers.submit(() -> ccm.requestWrite(first, change(b)));
            try {
                one.get(150, TimeUnit.MILLISECONDS);
                throw new AssertionError("facade did not wait for a conflicting lock");
            } catch (TimeoutException expected) {
                // The first transaction is now waiting for the second.
            }
            Future<AccessResponse> two = workers.submit(() -> ccm.requestWrite(second, change(a)));
            require(get(two).status() == AccessStatus.ABORTED, "facade did not abort the victim");
            granted(get(one).status());
            require(ccm.commit(first).status() == TransactionStatus.COMMITTED, "survivor could not commit");
            require(ccm.commit(second).status() == TransactionStatus.ABORTED, "victim committed after abort");
            List<ScheduleOperation> schedule = ccm.getRecorder().committedSchedule();
            require(schedule.size() == 2 && schedule.stream().allMatch(op -> op.txId() == first),
                    "victim operations entered the committed schedule");
            require(new SerializabilityVerifier().isConflictSerializable(schedule), "schedule is not serializable");
        } finally {
            workers.shutdownNow();
        }
    }

    private static ResourceId table(String name) {
        return ResourceId.ofTable(name);
    }

    private static ResourceId record(String name, int slot) {
        return ResourceId.ofRecord(name, new RID(new PageId(name + "-id", 0), slot));
    }

    private static DataChange change(ResourceId resource) {
        Value before = new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty());
        Value after = new Value(DataType.INTEGER, Optional.of(2), Optional.empty(), Optional.empty());
        return new DataChange(ChangeOperation.UPDATE, resource.tableName(), resource.rid().orElseThrow(),
                new RecordImage(true, List.of(before)), new RecordImage(true, List.of(after)));
    }

    private static <T> T get(Future<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void granted(AccessStatus status) {
        require(status == AccessStatus.GRANTED, "expected GRANTED but got " + status);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed out waiting for the request to block");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            require(latch.await(5, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rollback interrupted", e);
        }
    }

    private static ExecutorService workers() {
        return Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "cc2-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void test(String name, CheckedTest body) throws Exception {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    @FunctionalInterface
    private interface CheckedTest {
        void run() throws Exception;
    }

    private static final class Fixture implements AutoCloseable {
        final ExecutorService workers = workers();
        final Map<Long, Transaction> transactions = new ConcurrentHashMap<>();
        final List<Long> aborted = new CopyOnWriteArrayList<>();
        final DeadlockHandler graph = new DeadlockHandler();
        final LockBasedProtocol protocol = new LockBasedProtocol(graph, this::abort);
        final Consumer<Transaction> rollback;

        Fixture() {
            this(tx -> { });
        }

        Fixture(Consumer<Transaction> rollback) {
            this.rollback = rollback;
        }

        Transaction begin(long id) {
            Transaction tx = new Transaction(id, id);
            transactions.put(id, tx);
            protocol.onBegin(tx);
            return tx;
        }

        <T> Future<T> submit(Callable<T> task) {
            return workers.submit(task);
        }

        void waiting(Transaction tx) {
            await(() -> protocol.getWaitingTransactions().contains(tx.getTxId()));
        }

        void commit(Transaction tx) {
            tx.setStatus(TransactionStatus.COMMITTED);
            protocol.release(tx);
        }

        void abort(long id) {
            Transaction tx = transactions.get(id);
            synchronized (tx) {
                if (tx.getStatus() == TransactionStatus.ABORTED || tx.getStatus() == TransactionStatus.COMMITTED) {
                    return;
                }
                tx.setStatus(TransactionStatus.FAILED);
                rollback.accept(tx);
                tx.setStatus(TransactionStatus.ABORTED);
                protocol.release(tx);
                aborted.add(id);
            }
        }

        @Override
        public void close() {
            protocol.reset();
            workers.shutdownNow();
            try {
                require(workers.awaitTermination(2, TimeUnit.SECONDS), "test workers did not stop");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test cleanup interrupted", e);
            }
        }
    }
}
