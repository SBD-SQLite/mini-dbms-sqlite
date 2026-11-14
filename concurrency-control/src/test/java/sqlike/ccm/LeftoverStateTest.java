package sqlike.ccm;

import static sqlike.ccm.Check.change;
import static sqlike.ccm.Check.record;
import static sqlike.ccm.Check.require;

import sqlike.shared.AccessStatus;
import sqlike.shared.ProtocolType;
import sqlike.shared.TransactionStatus;

/** leftoverState() must see what an unfinished transaction holds, and nothing after it finishes. */
public final class LeftoverStateTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("LeftoverState");
        for (ProtocolType protocol : ProtocolType.values()) {
            check.test(protocol + ": clean before, dirty while active, clean after commit",
                    () -> commitCleans(protocol));
            check.test(protocol + ": clean after abort", () -> abortCleans(protocol));
            check.test(protocol + ": initAfterRecovery clears a stuck transaction", () -> resetCleans(protocol));
        }
        check.done();
    }

    private static ConcurrencyControlManager open(ProtocolType protocol) {
        ConcurrencyControlManager ccm = new ConcurrencyControlManager(new MockFRM(), new CCMConfig(protocol, 5000L));
        ccm.initAfterRecovery(0);
        return ccm;
    }

    private static long writeOne(ConcurrencyControlManager ccm) {
        long tx = ccm.beginTransaction().txId();
        require(ccm.requestRead(tx, record("items", 0)).status() == AccessStatus.GRANTED, "read granted");
        require(ccm.requestWrite(tx, change("items", 0)).status() == AccessStatus.GRANTED, "write granted");
        return tx;
    }

    private static void commitCleans(ProtocolType protocol) {
        ConcurrencyControlManager ccm = open(protocol);
        require(ccm.leftoverState().isEmpty(), "fresh CCM is clean: " + ccm.leftoverState());
        long tx = writeOne(ccm);
        require(!ccm.leftoverState().isEmpty(), "an active writer must show up as leftover");
        require(ccm.commit(tx).status() == TransactionStatus.COMMITTED, "commit");
        require(ccm.leftoverState().isEmpty(), "clean after commit: " + ccm.leftoverState());
    }

    private static void abortCleans(ProtocolType protocol) {
        ConcurrencyControlManager ccm = open(protocol);
        long tx = writeOne(ccm);
        require(!ccm.leftoverState().isEmpty(), "an active writer must show up as leftover");
        require(ccm.abort(tx).status() == TransactionStatus.ABORTED, "abort");
        require(ccm.leftoverState().isEmpty(), "clean after abort: " + ccm.leftoverState());
    }

    private static void resetCleans(ProtocolType protocol) {
        ConcurrencyControlManager ccm = open(protocol);
        writeOne(ccm);
        require(!ccm.leftoverState().isEmpty(), "an active writer must show up as leftover");
        ccm.initAfterRecovery(10);
        require(ccm.leftoverState().isEmpty(), "clean after initAfterRecovery: " + ccm.leftoverState());
    }
}
