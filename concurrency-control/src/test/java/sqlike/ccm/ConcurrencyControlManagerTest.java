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
}
