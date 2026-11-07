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
        check.test("commit releases locks only after COMMIT is logged", ConcurrencyControlManagerTest::commitOrder);
        check.test("a failed COMMIT log is an unknown outcome, not an abort", ConcurrencyControlManagerTest::commitUnknown);
        check.test("a transient rollback failure is retried", ConcurrencyControlManagerTest::rollbackRetried);
        check.test("a failed rollback keeps the transaction FAILED and its locks", ConcurrencyControlManagerTest::rollbackStuck);
        check.test("a failed logAbort keeps the locks until a retry works", ConcurrencyControlManagerTest::logAbortStuck);
        check.test("write access locks without logging", ConcurrencyControlManagerTest::writeAccess);
        check.test("a schema write locks the catalog and logs one schema change", ConcurrencyControlManagerTest::schemaWrite);
        check.test("a failed schema log aborts the transaction", ConcurrencyControlManagerTest::schemaWriteFails);
        check.test("abort waits for a running apply", ConcurrencyControlManagerTest::abortWaitsForApply);
        check.test("an apply that never ends leaves the transaction FAILED", ConcurrencyControlManagerTest::applyTimeoutOnAbort);
        check.test("commit does not run while an apply is in flight", ConcurrencyControlManagerTest::applyTimeoutOnCommit);
        check.test("pause holds new transactions but not running ones", ConcurrencyControlManagerTest::gate);
        check.test("initAfterRecovery keeps a paused gate closed", ConcurrencyControlManagerTest::recoveryKeepsGate);
        check.test("the protocol can change only when idle", ConcurrencyControlManagerTest::protocolChange);
        check.test("the active protocol and the default are described", ConcurrencyControlManagerTest::describe);
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

    private static void commitOrder() throws Exception {
        Rig r = rig();
        long writer = begin(r);
        long waiter = begin(r);
        granted(r.ccm().requestWrite(writer, change("a", 0)));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AccessResponse> blocked = pool.submit(() -> r.ccm().requestWrite(waiter, change("a", 1)));
            expectBlocked(blocked);
            TransactionResponse commit = r.ccm().commit(writer);
            require(commit.status() == TransactionStatus.COMMITTED && commit.error().isEmpty(), "commit succeeds");
            require(r.frm().wasCommitted(writer), "COMMIT must be logged");
            granted(get(blocked));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void commitUnknown() throws Exception {
        Rig r = rig();
        long first = begin(r);
        long second = begin(r);
        granted(r.ccm().requestWrite(first, change("a", 0)));
        r.frm().setFailOnOperation("logCommit", 1);
        TransactionResponse commit = r.ccm().commit(first);
        require(commit.status() == TransactionStatus.PARTIALLY_COMMITTED, "the outcome stays unknown");
        require(code(commit.error()) == ErrorCode.WAL_FAILURE, "the error must be WAL_FAILURE");
        require(!r.frm().wasRolledBack(first) && !r.frm().wasCommitted(first), "no undo, no commit");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AccessResponse> blocked = pool.submit(() -> r.ccm().requestWrite(second, change("a", 1)));
            expectBlocked(blocked);
        } finally {
            pool.shutdownNow();
        }
        require(code(r.ccm().beginTransaction().error()) == ErrorCode.INVALID_STATE, "new transactions are refused");
        TransactionResponse abort = r.ccm().abort(first);
        require(abort.status() == TransactionStatus.PARTIALLY_COMMITTED && code(abort.error()) == ErrorCode.INVALID_STATE,
                "abort must not undo a commit that may have happened");
        r.ccm().initAfterRecovery(10);
        require(r.ccm().beginTransaction().txId() == 11, "recovery reopens the manager");
    }

    private static void rollbackRetried() {
        Rig r = rig();
        long tx = begin(r);
        granted(r.ccm().requestWrite(tx, change("a", 0)));
        r.frm().setFailOnOperation("rollback", 2);
        TransactionResponse response = r.ccm().abort(tx);
        require(response.status() == TransactionStatus.ABORTED && response.error().isEmpty(), "abort succeeds");
        require(r.frm().wasRolledBack(tx) && r.frm().wasAborted(tx), "rollback then ABORT");
        require(code(r.ccm().abort(tx).error()) == ErrorCode.INVALID_STATE, "a second abort is refused");
    }

    private static void rollbackStuck() throws Exception {
        Rig r = rig();
        long first = begin(r);
        long second = begin(r);
        granted(r.ccm().requestWrite(first, change("a", 0)));
        r.frm().setFailOnOperation("rollback", 100);
        TransactionResponse stuck = r.ccm().abort(first);
        require(stuck.status() == TransactionStatus.FAILED && code(stuck.error()) == ErrorCode.WAL_FAILURE,
                "a failed rollback leaves the transaction FAILED");
        require(!r.frm().wasAborted(first), "no ABORT record without a finished undo");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AccessResponse> blocked = pool.submit(() -> r.ccm().requestWrite(second, change("a", 1)));
            expectBlocked(blocked);
            r.frm().setFailOnOperation("rollback", 0);
            require(r.ccm().abort(first).status() == TransactionStatus.ABORTED, "the retry finishes the abort");
            granted(get(blocked));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void logAbortStuck() {
        Rig r = rig();
        long tx = begin(r);
        granted(r.ccm().requestWrite(tx, change("a", 0)));
        r.frm().setFailOnOperation("logAbort", 100);
        require(r.ccm().abort(tx).status() == TransactionStatus.FAILED, "ABORT could not be logged");
        require(r.frm().wasRolledBack(tx) && !r.frm().wasAborted(tx), "undo done, ABORT missing");
        r.frm().setFailOnOperation("logAbort", 0);
        require(r.ccm().abort(tx).status() == TransactionStatus.ABORTED, "the retry logs ABORT");
    }

    private static void writeAccess() throws Exception {
        Rig r = rig();
        long first = begin(r);
        long second = begin(r);
        AccessResponse access = r.ccm().requestWriteAccess(first, ResourceId.ofTable("a"));
        granted(access);
        require(access.changeLSN().isEmpty(), "write access has no LSN");
        require(changeRecords(r) == 0, "write access must not write a log record");
        granted(r.ccm().requestWrite(first, change("a", 0)));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AccessResponse> blocked = pool.submit(() -> r.ccm().requestWriteAccess(second, ResourceId.ofTable("a")));
            expectBlocked(blocked);
            r.ccm().commit(first);
            granted(get(blocked));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void schemaWrite() throws Exception {
        Rig r = rig();
        long ddl = begin(r);
        long reader = begin(r);
        AccessResponse response = r.ccm().requestSchemaWrite(ddl, new SchemaChange(null, null));
        granted(response);
        require(response.changeLSN().orElse(0L) > 0, "a schema write carries a positive LSN");
        require(r.frm().getLog().stream().anyMatch(line -> line.contains("SCHEMA_CHANGE tx=" + ddl)),
                "the schema change must be logged");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AccessResponse> blocked = pool.submit(() -> r.ccm().requestRead(reader, ResourceId.ofCatalog()));
            expectBlocked(blocked);
            r.ccm().commit(ddl);
            granted(get(blocked));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void schemaWriteFails() {
        Rig r = rig();
        long ddl = begin(r);
        r.frm().setFailOnOperation("logSchemaChange", 1);
        AccessResponse failed = r.ccm().requestSchemaWrite(ddl, new SchemaChange(null, null));
        require(failed.status() == AccessStatus.ABORTED && code(failed.error()) == ErrorCode.WAL_FAILURE,
                "the schema write must be refused with WAL_FAILURE");
        require(r.frm().wasRolledBack(ddl), "the transaction must be rolled back");
    }

    private static void abortWaitsForApply() throws Exception {
        Rig r = rig(fastConfig().withApplyDrainTimeoutMs(2000));
        long tx = begin(r);
        long lsn = r.ccm().requestWrite(tx, change("a", 0)).changeLSN().orElseThrow();
        require(r.ccm().beginApply(tx, lsn), "the granted LSN may be applied");
        require(!r.ccm().beginApply(tx, lsn + 99), "an LSN that was not granted is refused");
        require(!r.ccm().beginApply(999, lsn), "an unknown transaction is refused");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TransactionResponse> abort = pool.submit(() -> r.ccm().abort(tx));
            expectBlocked(abort);
            require(!r.frm().wasRolledBack(tx), "no undo while the apply is running");
            r.ccm().endApply(tx, lsn);
            require(get(abort).status() == TransactionStatus.ABORTED, "abort finishes after the apply");
            require(r.frm().wasRolledBack(tx), "undo runs once the apply ended");
            require(!r.ccm().beginApply(tx, lsn), "no apply after the abort");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void applyTimeoutOnAbort() {
        Rig r = rig();
        long tx = begin(r);
        long lsn = r.ccm().requestWrite(tx, change("a", 0)).changeLSN().orElseThrow();
        require(r.ccm().beginApply(tx, lsn), "apply starts");
        TransactionResponse stuck = r.ccm().abort(tx);
        require(stuck.status() == TransactionStatus.FAILED && code(stuck.error()) == ErrorCode.WAL_FAILURE,
                "a stuck apply leaves the transaction FAILED");
        require(!r.frm().wasRolledBack(tx), "no undo parallel to a physical apply");
        r.ccm().endApply(tx, lsn);
        require(r.ccm().abort(tx).status() == TransactionStatus.ABORTED, "the retry finishes the abort");
    }

    private static void applyTimeoutOnCommit() {
        Rig r = rig();
        long tx = begin(r);
        long lsn = r.ccm().requestWrite(tx, change("a", 0)).changeLSN().orElseThrow();
        require(r.ccm().beginApply(tx, lsn), "apply starts");
        TransactionResponse response = r.ccm().commit(tx);
        require(response.status() == TransactionStatus.FAILED && code(response.error()) == ErrorCode.WAL_FAILURE,
                "commit must fail while an apply is running");
        require(!r.frm().wasCommitted(tx), "COMMIT must not be logged");
        r.ccm().endApply(tx, lsn);
        require(r.ccm().abort(tx).status() == TransactionStatus.ABORTED, "the transaction can still be aborted");
    }

    private static void gate() throws Exception {
        Rig r = rig();
        long running = begin(r);
        r.ccm().pauseNewTransactions();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TransactionResponse> held = pool.submit(() -> r.ccm().beginTransaction());
            expectBlocked(held);
            granted(r.ccm().requestWrite(running, change("a", 0)));
            require(!r.ccm().awaitNoActiveTransactions(50), "must time out while a transaction runs");
            require(r.ccm().commit(running).status() == TransactionStatus.COMMITTED, "a running transaction may commit");
            require(r.ccm().awaitNoActiveTransactions(500), "idle after the commit");
            r.ccm().resumeTransactions();
            require(get(held).status() == TransactionStatus.ACTIVE, "the held transaction starts after resume");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void recoveryKeepsGate() throws Exception {
        Rig r = rig();
        r.ccm().pauseNewTransactions();
        r.ccm().initAfterRecovery(5);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TransactionResponse> held = pool.submit(() -> r.ccm().beginTransaction());
            expectBlocked(held);
            r.ccm().resumeTransactions();
            require(get(held).txId() == 6, "ids continue after lastTxId once the gate opens");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void protocolChange() throws Exception {
        Rig r = rig();
        long tx = begin(r);
        rejects(IllegalStateException.class, () -> r.ccm().setProtocol(ProtocolType.TIMESTAMP_BASED));
        r.ccm().commit(tx);
        r.ccm().setProtocol(ProtocolType.TIMESTAMP_BASED);
        require(r.ccm().getActiveProtocol() == ProtocolType.TIMESTAMP_BASED, "the protocol must change when idle");
    }

    private static void describe() {
        MockFRM frm = new MockFRM();
        require(new ConcurrencyControlManager(frm).describeProtocol()
                .equals("Active protocol: LOCK_BASED (default: LOCK_BASED)"), "default description");
        CCMConfig timestamp = new CCMConfig(ProtocolType.TIMESTAMP_BASED, 5000L);
        require(new ConcurrencyControlManager(frm, timestamp).describeProtocol()
                .equals("Active protocol: TIMESTAMP_BASED (default: LOCK_BASED)"), "configured description");
    }
}
