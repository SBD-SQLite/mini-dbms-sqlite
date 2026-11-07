package sqlike.ccm;

import static sqlike.ccm.Check.expectBlocked;
import static sqlike.ccm.Check.get;
import static sqlike.ccm.Check.require;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sqlike.shared.TransactionStatus;

/** Apply tracking on a transaction. */
public final class TransactionTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("Transaction");
        check.test("only a granted LSN may be applied", TransactionTest::grantedOnly);
        check.test("a finished transaction cannot start an apply", TransactionTest::inactiveRefused);
        check.test("awaitNoApply waits for the running apply", TransactionTest::waitsForApply);
        check.done();
    }

    private static void grantedOnly() {
        Transaction tx = new Transaction(1, 1);
        require(!tx.beginApply(5), "an LSN that was never granted must be refused");
        tx.grantLsn(5);
        require(tx.beginApply(5), "a granted LSN must be accepted");
        require(!tx.awaitNoApply(30), "an apply is running");
        tx.endApply();
        require(tx.awaitNoApply(30), "no apply is running any more");
        tx.endApply();
        require(tx.awaitNoApply(30), "an extra endApply must not break the counter");
    }

    private static void inactiveRefused() {
        Transaction tx = new Transaction(1, 1);
        tx.grantLsn(5);
        tx.setStatus(TransactionStatus.FAILED);
        require(!tx.beginApply(5), "a FAILED transaction must not start an apply");
    }

    private static void waitsForApply() throws Exception {
        Transaction tx = new Transaction(1, 1);
        tx.grantLsn(5);
        require(tx.beginApply(5), "apply must start");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> drained = pool.submit(() -> tx.awaitNoApply(2000));
            expectBlocked(drained);
            tx.endApply();
            require(get(drained), "the waiter must see the apply finish");
        } finally {
            pool.shutdownNow();
        }
    }
}
