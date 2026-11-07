package sqlike.ccm;

import static sqlike.ccm.Check.expectBlocked;
import static sqlike.ccm.Check.get;
import static sqlike.ccm.Check.require;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sqlike.shared.AccessStatus;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionStatus;

/** CC4 checks for strict timestamp ordering on table-level resources. */
public final class TimestampProtocolTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check("CC4");
        check.test("read older than a committed write is aborted", TimestampProtocolTest::staleRead);
        check.test("write older than a newer reader is aborted", TimestampProtocolTest::staleWrite);
        check.test("write older than an uncommitted writer is aborted", TimestampProtocolTest::olderThanWriter);
        check.test("newer read waits for an uncommitted writer without a cycle", TimestampProtocolTest::waitsForWriter);
        check.test("aborted writer leaves no write timestamp behind", TimestampProtocolTest::rolledBackWriter);
        check.test("retry after a violation gets a newer timestamp", TimestampProtocolTest::retryAfterViolation);
        check.test("record and table requests share one resource", TimestampProtocolTest::recordEqualsTable);
        check.done();
    }

    private static Transaction tx(long id) {
        return new Transaction(id, id);
    }

    private static void commit(TimestampProtocol protocol, Transaction tx) {
        tx.setStatus(TransactionStatus.COMMITTED);
        protocol.release(tx);
    }

    private static void abort(TimestampProtocol protocol, Transaction tx) {
        tx.setStatus(TransactionStatus.ABORTED);
        protocol.release(tx);
    }

    private static void staleRead() {
        TimestampProtocol protocol = new TimestampProtocol();
        ResourceId a = Resources.table("a");
        Transaction older = tx(1);
        Transaction newer = tx(2);
        require(protocol.checkWrite(newer, a) == AccessStatus.GRANTED, "newer write must be granted");
        commit(protocol, newer);
        require(protocol.checkRead(older, a) == AccessStatus.ABORTED, "stale read must be aborted");
    }

    private static void staleWrite() {
        TimestampProtocol protocol = new TimestampProtocol();
        ResourceId a = Resources.table("a");
        Transaction older = tx(1);
        Transaction newer = tx(2);
        require(protocol.checkRead(newer, a) == AccessStatus.GRANTED, "newer read must be granted");
        require(protocol.checkWrite(older, a) == AccessStatus.ABORTED, "write below the read timestamp must abort");
    }

    private static void olderThanWriter() {
        TimestampProtocol protocol = new TimestampProtocol();
        ResourceId a = Resources.table("a");
        require(protocol.checkWrite(tx(2), a) == AccessStatus.GRANTED, "newer write must be granted");
        require(protocol.checkWrite(tx(1), a) == AccessStatus.ABORTED, "older write must be aborted");
    }

    private static void waitsForWriter() throws Exception {
        TimestampProtocol protocol = new TimestampProtocol();
        ResourceId a = Resources.table("a");
        Transaction writer = tx(1);
        Transaction reader = tx(2);
        require(protocol.checkWrite(writer, a) == AccessStatus.GRANTED, "writer must be granted");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AccessStatus> read = pool.submit(() -> protocol.checkRead(reader, a));
            expectBlocked(read);
            commit(protocol, writer);
            require(get(read) == AccessStatus.GRANTED, "waiting reader must be granted after the commit");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void rolledBackWriter() {
        TimestampProtocol protocol = new TimestampProtocol();
        ResourceId a = Resources.table("a");
        Transaction writer = tx(2);
        require(protocol.checkWrite(writer, a) == AccessStatus.GRANTED, "writer must be granted");
        abort(protocol, writer);
        require(protocol.checkRead(tx(1), a) == AccessStatus.GRANTED,
                "a rolled-back write must not make older reads fail");
    }

    private static void retryAfterViolation() {
        TimestampProtocol protocol = new TimestampProtocol();
        ResourceId a = Resources.table("a");
        Transaction newer = tx(2);
        require(protocol.checkWrite(newer, a) == AccessStatus.GRANTED, "newer write must be granted");
        commit(protocol, newer);
        Transaction violator = tx(1);
        require(protocol.checkWrite(violator, a) == AccessStatus.ABORTED, "older write must be aborted");
        abort(protocol, violator);
        Transaction retry = tx(3);
        require(protocol.checkWrite(retry, a) == AccessStatus.GRANTED, "retry with a new timestamp must succeed");
    }

    private static void recordEqualsTable() throws Exception {
        TimestampProtocol protocol = new TimestampProtocol();
        Transaction writer = tx(1);
        Transaction scanner = tx(2);
        require(protocol.checkWrite(writer, Check.record("items", 3)) == AccessStatus.GRANTED,
                "record write must be granted");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AccessStatus> scan = pool.submit(() -> protocol.checkRead(scanner, Resources.table("items")));
            expectBlocked(scan);
            commit(protocol, writer);
            require(get(scan) == AccessStatus.GRANTED, "table read must wait for the record writer");
        } finally {
            pool.shutdownNow();
        }
    }
}
