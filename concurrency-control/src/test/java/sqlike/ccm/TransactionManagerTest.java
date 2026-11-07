package sqlike.ccm;

import static sqlike.ccm.Check.rejects;
import static sqlike.ccm.Check.require;

import sqlike.shared.TransactionStatus;

/** CC1 checks for the transaction manager. */
public final class TransactionManagerTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("CC1 TransactionManager");
        check.test("valid status transitions", TransactionManagerTest::validTransitions);
        check.test("invalid and final transitions are rejected", TransactionManagerTest::invalidTransitions);
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
}
