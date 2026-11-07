package sqlike.ccm;

import static sqlike.ccm.Check.change;
import static sqlike.ccm.Check.expectBlocked;
import static sqlike.ccm.Check.get;
import static sqlike.ccm.Check.record;
import static sqlike.ccm.Check.rejects;
import static sqlike.ccm.Check.require;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;
import sqlike.shared.SchemaChange;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;

/** CC1 and CC3 checks for the CCM facade, using MockFRM. */
public final class ConcurrencyControlManagerTest {

    private record Rig(MockFRM frm, ConcurrencyControlManager ccm) {
    }

    public static void main(String[] args) throws Exception {
        Check check = new Check("CCM facade");
        check.test("begin is rejected until recovery finished", ConcurrencyControlManagerTest::beginBeforeReady);
        check.test("a failed logBegin aborts the transaction", ConcurrencyControlManagerTest::logBeginFails);
        check.test("finished transactions report that they were aborted", ConcurrencyControlManagerTest::abortedMessages);
        check.test("a granted write logs one change and a read logs none", ConcurrencyControlManagerTest::oneLogPerWrite);
        check.test("a failed change log aborts the transaction", ConcurrencyControlManagerTest::writeLogFails);
        check.done();
    }

    private static CCMConfig fastConfig() {
        return CCMConfig.defaults().withRollbackRetry(3, 5).withApplyDrainTimeoutMs(300);
    }

    private static Rig rig() {
        return rig(fastConfig());
    }

    private static Rig rig(CCMConfig config) {
        MockFRM frm = new MockFRM();
        ConcurrencyControlManager ccm = new ConcurrencyControlManager(frm, config);
        ccm.initAfterRecovery(0);
        return new Rig(frm, ccm);
    }

    private static long begin(Rig rig) {
        TransactionResponse response = rig.ccm().beginTransaction();
        require(response.status() == TransactionStatus.ACTIVE, "begin must return an ACTIVE transaction");
        return response.txId();
    }

    private static void granted(AccessResponse response) {
        require(response.status() == AccessStatus.GRANTED, "expected GRANTED but was " + response);
    }

    private static ErrorCode code(Optional<ErrorInfo> error) {
        return error.map(ErrorInfo::code).orElse(null);
    }

    private static void beginBeforeReady() {
        MockFRM frm = new MockFRM();
        ConcurrencyControlManager ccm = new ConcurrencyControlManager(frm, fastConfig());
        TransactionResponse early = ccm.beginTransaction();
        require(early.status() == TransactionStatus.FAILED && early.txId() == 0, "no transaction may start");
        require(code(early.error()) == ErrorCode.INVALID_STATE, "the error must be INVALID_STATE");
        ccm.initAfterRecovery(7);
        require(ccm.beginTransaction().txId() == 8, "ids continue after lastTxId");
    }

    private static void logBeginFails() {
        Rig r = rig();
        r.frm().setFailOnOperation("logBegin", 1);
        TransactionResponse failed = r.ccm().beginTransaction();
        require(failed.status() == TransactionStatus.ABORTED, "status must be ABORTED");
        require(code(failed.error()) == ErrorCode.WAL_FAILURE, "error must be WAL_FAILURE");
        require(!r.frm().wasRolledBack(failed.txId()), "nothing was logged, so nothing is undone");
        TransactionResponse next = r.ccm().beginTransaction();
        require(next.status() == TransactionStatus.ACTIVE && next.txId() > failed.txId(), "ids are not reused");
    }

    private static void abortedMessages() {
        Rig r = rig();
        long tx = begin(r);
        r.ccm().abort(tx);
        AccessResponse read = r.ccm().requestRead(tx, record("a", 0));
        AccessResponse write = r.ccm().requestWrite(tx, change("a", 0));
        TransactionResponse commit = r.ccm().commit(tx);
        for (Optional<ErrorInfo> error : java.util.List.of(read.error(), write.error(), commit.error())) {
            require(code(error) == ErrorCode.TRANSACTION_ABORTED, "expected TRANSACTION_ABORTED");
            require(error.get().message().contains("has been aborted"), "message must say the transaction was aborted");
        }
        require(read.status() == AccessStatus.ABORTED && write.status() == AccessStatus.ABORTED, "access is refused");

        long done = begin(r);
        r.ccm().commit(done);
        require(code(r.ccm().requestRead(done, record("a", 0)).error()) == ErrorCode.INVALID_STATE,
                "a committed transaction is INVALID_STATE, not aborted");
    }

    private static long changeRecords(Rig r) {
        return r.frm().getLog().stream().filter(line -> line.contains(" CHANGE tx=")).count();
    }

    private static void oneLogPerWrite() {
        Rig r = rig();
        long tx = begin(r);
        granted(r.ccm().requestRead(tx, record("a", 0)));
        require(changeRecords(r) == 0, "reads must not be logged");
        AccessResponse write = r.ccm().requestWrite(tx, change("a", 0));
        granted(write);
        require(write.changeLSN().orElse(0L) > 0, "a granted write carries a positive LSN");
        require(changeRecords(r) == 1, "exactly one change record per granted write");
    }

    private static void writeLogFails() {
        Rig r = rig();
        long tx = begin(r);
        r.frm().setFailOnOperation("logChange", 1);
        AccessResponse failed = r.ccm().requestWrite(tx, change("a", 0));
        require(failed.status() == AccessStatus.ABORTED && code(failed.error()) == ErrorCode.WAL_FAILURE,
                "the write must be refused with WAL_FAILURE");
        require(r.frm().wasRolledBack(tx) && r.frm().wasAborted(tx), "the transaction must be rolled back");
        require(code(r.ccm().requestRead(tx, record("a", 0)).error()) == ErrorCode.TRANSACTION_ABORTED,
                "later requests see the abort");
        long other = begin(r);
        granted(r.ccm().requestWrite(other, change("a", 0)));
    }
}
