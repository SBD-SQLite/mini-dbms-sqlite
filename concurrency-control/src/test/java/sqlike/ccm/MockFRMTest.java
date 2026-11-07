package sqlike.ccm;

import static sqlike.ccm.Check.rejects;
import static sqlike.ccm.Check.require;

/** Failure injection in the mock FRM. */
public final class MockFRMTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("MockFRM");
        check.test("an operation fails the requested number of times", MockFRMTest::failsNTimes);
        check.test("injection is per operation and can be cleared", MockFRMTest::perOperation);
        check.test("the one-argument form fails once", MockFRMTest::singleShot);
        check.done();
    }

    private static void failsNTimes() throws Exception {
        MockFRM frm = new MockFRM();
        frm.setFailOnOperation("rollback", 2);
        rejects(RuntimeException.class, () -> frm.rollback(1));
        rejects(RuntimeException.class, () -> frm.rollback(1));
        frm.rollback(1);
        require(frm.wasRolledBack(1), "the third call must succeed");
    }

    private static void perOperation() throws Exception {
        MockFRM frm = new MockFRM();
        frm.setFailOnOperation("logAbort", 5);
        frm.logBegin(2);
        rejects(RuntimeException.class, () -> frm.logAbort(2));
        frm.setFailOnOperation("logAbort", 0);
        frm.logAbort(2);
        require(frm.wasAborted(2), "cleared injection must let the call through");
    }

    private static void singleShot() throws Exception {
        MockFRM frm = new MockFRM();
        frm.setFailOnOperation("logBegin");
        rejects(RuntimeException.class, () -> frm.logBegin(3));
        frm.logBegin(3);
    }
}
