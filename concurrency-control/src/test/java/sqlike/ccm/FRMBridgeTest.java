package sqlike.ccm;

import static sqlike.ccm.Check.change;
import static sqlike.ccm.Check.rejects;
import static sqlike.ccm.Check.require;

import sqlike.shared.DataChange;
import sqlike.shared.SchemaChange;

/** CC3 checks for the FRM bridge. */
public final class FRMBridgeTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("CC3 FRMBridge");
        check.test("a change is logged once and returns the FRM LSN", FRMBridgeTest::logsOnce);
        check.test("FRM exceptions become FrmFailureException", FRMBridgeTest::wrapsFailures);
        check.test("a non-positive LSN is a failure", FRMBridgeTest::rejectsBadLsn);
        check.test("rollback is retried and then logAbort follows", FRMBridgeTest::retriesRollback);
        check.test("exhausted rollback does not write ABORT", FRMBridgeTest::rollbackExhausted);
        check.test("logAbort is retried and can fail after a good rollback", FRMBridgeTest::logAbortFails);
        check.done();
    }

    private static FRMBridge bridge(MockFRM frm) {
        return new FRMBridge(frm, CCMConfig.defaults().withRollbackRetry(3, 1));
    }

    private static void logsOnce() {
        MockFRM frm = new MockFRM();
        FRMBridge bridge = bridge(frm);
        bridge.logBegin(1);
        long lsn = bridge.logWrite(1, change("a", 0));
        long schemaLsn = bridge.logSchema(1, new SchemaChange(null, null));
        require(lsn > 0 && schemaLsn > lsn, "LSNs must be positive and increasing");
        bridge.logCommit(1);
        long changes = frm.getLog().stream().filter(line -> line.contains(" CHANGE tx=1")).count();
        require(changes == 1, "exactly one change record is expected");
        require(frm.wasCommitted(1), "COMMIT must reach the FRM");
    }

    private static void wrapsFailures() throws Exception {
        MockFRM frm = new MockFRM();
        FRMBridge bridge = bridge(frm);
        frm.setFailOnOperation("logChange", 1);
        try {
            bridge.logWrite(1, change("a", 0));
            throw new AssertionError("logWrite must fail");
        } catch (FrmFailureException e) {
            require("logChange".equals(e.operation()), "the failed operation must be named");
        }
        frm.setFailOnOperation("logCommit", 1);
        rejects(FrmFailureException.class, () -> bridge.logCommit(1));
        frm.setFailOnOperation("logBegin", 1);
        rejects(FrmFailureException.class, () -> bridge.logBegin(2));
    }

    private static void rejectsBadLsn() throws Exception {
        MockFRM zeroLsn = new MockFRM() {
            @Override
            public synchronized long logChange(long txId, DataChange change) {
                return 0L;
            }
        };
        rejects(FrmFailureException.class, () -> bridge(zeroLsn).logWrite(1, change("a", 0)));
    }

    private static void retriesRollback() {
        MockFRM frm = new MockFRM();
        FRMBridge bridge = bridge(frm);
        frm.setFailOnOperation("rollback", 2);
        bridge.rollbackThenLogAbort(7);
        require(frm.wasRolledBack(7) && frm.wasAborted(7), "rollback then ABORT after two failures");
    }

    private static void rollbackExhausted() throws Exception {
        MockFRM frm = new MockFRM();
        FRMBridge bridge = bridge(frm);
        frm.setFailOnOperation("rollback", 10);
        try {
            bridge.rollbackThenLogAbort(7);
            throw new AssertionError("rollback must fail after 3 attempts");
        } catch (FrmFailureException e) {
            require("rollback".equals(e.operation()), "rollback must be named");
        }
        require(!frm.wasAborted(7), "no ABORT record without a finished undo");
    }

    private static void logAbortFails() throws Exception {
        MockFRM frm = new MockFRM();
        FRMBridge bridge = bridge(frm);
        frm.setFailOnOperation("logAbort", 10);
        try {
            bridge.rollbackThenLogAbort(7);
            throw new AssertionError("logAbort must fail after 3 attempts");
        } catch (FrmFailureException e) {
            require("logAbort".equals(e.operation()), "logAbort must be named");
        }
        require(frm.wasRolledBack(7) && !frm.wasAborted(7), "undo done, ABORT missing");
    }
}
