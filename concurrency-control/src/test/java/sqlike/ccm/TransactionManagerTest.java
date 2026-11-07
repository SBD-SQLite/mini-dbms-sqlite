package sqlike.ccm;

import static sqlike.ccm.Check.expectBlocked;
import static sqlike.ccm.Check.get;
import static sqlike.ccm.Check.rejects;
import static sqlike.ccm.Check.require;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sqlike.shared.TransactionStatus;

/** CC1 checks for the transaction manager. */
public final class TransactionManagerTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("CC1 TransactionManager");
        check.test("valid status transitions", TransactionManagerTest::validTransitions);
        check.test("invalid and final transitions are rejected", TransactionManagerTest::invalidTransitions);
        check.test("pause holds new transactions until resume", TransactionManagerTest::pauseHoldsCreate);
        check.test("awaitNoActive times out while a transaction is unfinished", TransactionManagerTest::awaitTimesOut);
        check.test("awaitNoActive wakes when the last transaction ends", TransactionManagerTest::awaitWakes);
        check.test("pause is idempotent and recovery keeps the gate", TransactionManagerTest::resetKeepsGate);
        check.test("create is rejected before recovery", TransactionManagerTest::rejectedBeforeRecovery);
        check.test("recovery opens the manager after lastTxId", TransactionManagerTest::opensAfterRecovery);
        check.test("markNotReady stops new transactions until recovery", TransactionManagerTest::notReadyAgain);
        check.test("a waiting creator is rejected when the manager stops being ready", TransactionManagerTest::waiterRejected);
        check.test("runIfNoneActive only runs when idle", TransactionManagerTest::runIfNoneActive);
        check.done();
    }

    private static TransactionManager manager() {
        TransactionManager manager = new TransactionManager();
        manager.resetAfterRecovery(0);
        return manager;
    }

    private static void validTransitions() {
        TransactionManager m = manager();
        Transaction committed = m.create();
        m.setStatus(committed.getTxId(), TransactionStatus.PARTIALLY_COMMITTED);
        m.setStatus(committed.getTxId(), TransactionStatus.COMMITTED);
        require(committed.getStatus() == TransactionStatus.COMMITTED, "commit path failed");

        Transaction aborted = m.create();
        m.setStatus(aborted.getTxId(), TransactionStatus.FAILED);
        m.setStatus(aborted.getTxId(), TransactionStatus.ABORTED);
        require(aborted.getStatus() == TransactionStatus.ABORTED, "abort path failed");

        Transaction failedCommit = m.create();
        m.setStatus(failedCommit.getTxId(), TransactionStatus.PARTIALLY_COMMITTED);
        m.setStatus(failedCommit.getTxId(), TransactionStatus.FAILED);
        require(failedCommit.getStatus() == TransactionStatus.FAILED, "commit failure path failed");
    }

    private static void invalidTransitions() throws Exception {
        TransactionManager m = manager();
        long id = m.create().getTxId();
        rejects(IllegalStateException.class, () -> m.setStatus(id, TransactionStatus.COMMITTED));
        rejects(IllegalStateException.class, () -> m.setStatus(id, TransactionStatus.ABORTED));
        rejects(IllegalStateException.class, () -> m.setStatus(id, TransactionStatus.ACTIVE));
        m.setStatus(id, TransactionStatus.PARTIALLY_COMMITTED);
        m.setStatus(id, TransactionStatus.COMMITTED);
        for (TransactionStatus status : TransactionStatus.values()) {
            rejects(IllegalStateException.class, () -> m.setStatus(id, status));
        }

        long failed = m.create().getTxId();
        m.setStatus(failed, TransactionStatus.FAILED);
        rejects(IllegalStateException.class, () -> m.setStatus(failed, TransactionStatus.FAILED));
        rejects(IllegalStateException.class, () -> m.setStatus(failed, TransactionStatus.COMMITTED));
        m.setStatus(failed, TransactionStatus.ABORTED);
        rejects(IllegalStateException.class, () -> m.setStatus(failed, TransactionStatus.ACTIVE));
    }

    private static void pauseHoldsCreate() throws Exception {
        TransactionManager m = manager();
        Transaction running = m.create();
        m.pause();
        m.pause();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Transaction> blocked = pool.submit(() -> m.create());
            expectBlocked(blocked);
            m.setStatus(running.getTxId(), TransactionStatus.PARTIALLY_COMMITTED);
            m.setStatus(running.getTxId(), TransactionStatus.COMMITTED);
            m.resume();
            require(get(blocked).getTxId() == 2, "released transaction must get the next id");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitTimesOut() {
        TransactionManager m = manager();
        long id = m.create().getTxId();
        require(m.hasActive(), "a new transaction is unfinished");
        require(!m.awaitNoActive(50), "must time out while the transaction is active");
        m.setStatus(id, TransactionStatus.FAILED);
        require(!m.awaitNoActive(50), "a FAILED transaction is still unfinished");
        m.setStatus(id, TransactionStatus.ABORTED);
        require(m.awaitNoActive(50), "must be idle after ABORTED");
        require(!m.hasActive(), "no unfinished transaction remains");
    }

    private static void awaitWakes() throws Exception {
        TransactionManager m = manager();
        long id = m.create().getTxId();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> idle = pool.submit(() -> m.awaitNoActive(2000));
            expectBlocked(idle);
            m.setStatus(id, TransactionStatus.PARTIALLY_COMMITTED);
            m.setStatus(id, TransactionStatus.COMMITTED);
            require(get(idle), "the waiter must report idle");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void resetKeepsGate() throws Exception {
        TransactionManager m = manager();
        m.pause();
        m.resetAfterRecovery(5);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Transaction> blocked = pool.submit(() -> m.create());
            expectBlocked(blocked);
            m.resume();
            require(get(blocked).getTxId() == 6, "ids continue after lastTxId");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void rejectedBeforeRecovery() throws Exception {
        TransactionManager m = new TransactionManager();
        require(!m.isReady(), "a new manager is not ready");
        rejects(IllegalStateException.class, () -> m.create());
    }

    private static void opensAfterRecovery() {
        TransactionManager m = new TransactionManager();
        m.resetAfterRecovery(41);
        require(m.isReady(), "recovery makes the manager ready");
        Transaction tx = m.create();
        require(tx.getTxId() == 42 && tx.getTimestamp() == 42, "id and timestamp continue after lastTxId");
    }

    private static void notReadyAgain() throws Exception {
        TransactionManager m = manager();
        m.markNotReady();
        rejects(IllegalStateException.class, () -> m.create());
        m.resetAfterRecovery(0);
        require(m.create().getTxId() == 1, "recovery reopens the manager");
    }

    private static void waiterRejected() throws Exception {
        TransactionManager m = manager();
        m.pause();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Transaction> blocked = pool.submit(() -> m.create());
            expectBlocked(blocked);
            m.markNotReady();
            try {
                blocked.get(2, java.util.concurrent.TimeUnit.SECONDS);
                throw new AssertionError("the waiting creator must be rejected");
            } catch (java.util.concurrent.ExecutionException e) {
                require(e.getCause() instanceof IllegalStateException, "rejection must be an IllegalStateException");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void runIfNoneActive() throws Exception {
        TransactionManager m = manager();
        long id = m.create().getTxId();
        int[] runs = {0};
        rejects(IllegalStateException.class, () -> m.runIfNoneActive(() -> runs[0]++));
        require(runs[0] == 0, "the action must not run while a transaction is unfinished");
        m.setStatus(id, TransactionStatus.FAILED);
        m.setStatus(id, TransactionStatus.ABORTED);
        m.runIfNoneActive(() -> runs[0]++);
        require(runs[0] == 1, "the action must run once when idle");
    }
}
