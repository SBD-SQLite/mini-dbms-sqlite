package sqlike.qp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import sqlike.qo.PlanNodes;
import sqlike.shared.*;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.*;
import sqlike.qp.stub.StubRuntime.SessionState;
import sqlike.qp.stub.StubQueryRunner.Result;
import static sqlike.qp.port.QpContracts.payload;

/** QP-10 to QP-13 acceptance checks with real QO and explicit dependency stubs. */
public final class Qp5AcceptanceTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            restartPhase(args[0], Path.of(args[1]));
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Expected no arguments or a restart phase and snapshot");
        System.out.println("Real QO; stub QP1/QP2/QP3, SM, CCM/FRM.");
        test("QP-10 INSERT and QP-12 ABORT", Qp5AcceptanceTest::insertAndAbort);
        test("QP-11 SET, arithmetic, WHERE, and counts", Qp5AcceptanceTest::updateRows);
        test("QP-11 committed UPDATE survives restart", Qp5AcceptanceTest::updateSurvivesRestart);
        test("QP-12 BEGIN forms, unique IDs, and event", Qp5AcceptanceTest::beginTransactions);
        test("QP-12 auto-commit", Qp5AcceptanceTest::autoCommit);
        test("QP-12 access permission and WAIT", Qp5AcceptanceTest::accessAndWait);
        test("QP-12 COMMIT success and rejection", Qp5AcceptanceTest::commitResults);
        test("QP-13 CREATE INDEX and confirmation", Qp5AcceptanceTest::createIndexes);
        test("QP-13 IndexScan without table scan", Qp5AcceptanceTest::indexScan);
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) throw new AssertionError("Acceptance checks failed");
    }

    private static void insertAndAbort() throws Exception {
        TestDatabase database = new TestDatabase();
        database.execute("BEGIN;");
        Result inserted = database.execute("INSERT INTO employees VALUES (7, 1234.5);");

        assertAffected(1, inserted);
        assertEquals(List.of(List.of(7, 1234.5)), database.rows());

        Result aborted = database.execute("ABORT;");
        assertEquals("ABORT: ABORTED", aborted.message());
        assertEquals(List.of(), database.rows());
    }

    private static void updateRows() throws Exception {
        TestDatabase database = new TestDatabase();
        database.seed();

        Result arithmetic = database.execute("UPDATE employees SET salary = 1.05 * salary;");
        assertAffected(2, arithmetic);
        assertEquals(List.of(List.of(1, 1050.0), List.of(2, 2100.0)), database.rows());

        Result filtered = database.execute("UPDATE employees SET salary = 3000 WHERE id = 2;");
        assertAffected(1, filtered);
        assertEquals(List.of(List.of(1, 1050.0), List.of(2, 3000.0)), database.rows());

        Result allRows = database.execute("UPDATE employees SET salary = 5000;");
        assertAffected(2, allRows);
        assertEquals(List.of(List.of(1, 5000.0), List.of(2, 5000.0)), database.rows());
    }

    private static void updateSurvivesRestart() throws Exception {
        Path snapshot = Files.createTempDirectory("qp5-restart-").resolve("storage.stub");
        runProcess("write", snapshot);
        runProcess("read", snapshot);
    }

    private static void beginTransactions() throws Exception {
        TestDatabase database = new TestDatabase();
        Result begin = database.execute("BEGIN;");
        TxId first = database.session.getCurrentTxId().orElseThrow();
        assertTrue(begin.message().contains("ACTIVE"), begin.message());
        assertTrue(database.trace.contains("FRM.BEGIN." + first.value()), "Missing BEGIN event");

        database.execute("ABORT;");
        database.execute("BEGIN TRANSACTION;");
        TxId second = database.session.getCurrentTxId().orElseThrow();
        assertTrue(second.value() > first.value(), "CCM reused a transaction ID");
        assertTrue(database.trace.contains("FRM.BEGIN." + second.value()), "Missing BEGIN event");
        database.execute("ABORT;");
    }

    private static void autoCommit() throws Exception {
        TestDatabase database = new TestDatabase();
        database.execute("INSERT INTO employees VALUES (1, 1000);");

        TxId transaction = new TxId(database.storage.lastTxId());
        assertEquals(TransactionState.COMMITTED, database.transactions.getState(transaction));
        assertTrue(database.session.getCurrentTxId().isEmpty(), "Implicit transaction remains active");
        assertEquals(List.of(List.of(1, 1000.0)), database.rows());
    }

    private static void accessAndWait() throws Exception {
        TestDatabase database = new TestDatabase();
        database.transactions.scriptAccess(AccessStatus.WAIT, AccessStatus.GRANTED);
        database.transactions.releaseWait = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            Future<Result> insertion = worker.submit(() ->
                    database.execute("INSERT INTO employees VALUES (1, 1000);"));
            assertTrue(database.transactions.enteredWait.await(3, TimeUnit.SECONDS), "No CCM wait");
            assertTrue(!insertion.isDone(), "Query continued during WAIT");
            assertTrue(!database.trace.contains("SM.reserve"), "Storage accessed during WAIT");

            database.transactions.releaseWait.countDown();
            insertion.get(5, TimeUnit.SECONDS);
            assertBefore(database.trace, "CCM.access.WRITE", "SM.reserve");
        } finally {
            database.transactions.releaseWait.countDown();
            worker.shutdownNow();
        }

        database.trace.clear();
        assertEquals(List.of(List.of(1, 1000.0)), database.rows());
        assertBefore(database.trace, "CCM.access.READ", "SM.scan");
    }

    private static void commitResults() throws Exception {
        TestDatabase database = new TestDatabase();
        database.execute("BEGIN TRANSACTION;");
        database.execute("INSERT INTO employees VALUES (1, 1000);");
        assertEquals("COMMIT: COMMITTED", database.execute("COMMIT;").message());

        database.execute("BEGIN;");
        database.transactions.nextCommit = TransactionState.FAILED;
        Result rejected = database.runner.execute(database.session, "COMMIT;");
        assertTrue(!rejected.success(), "Rejected commit reported success");
        assertEquals("COMMIT rejected: FAILED", rejected.message());
        database.execute("ABORT;");
    }

    private static void createIndexes() throws Exception {
        TestDatabase database = new TestDatabase();
        Result hash = database.execute("CREATE INDEX by_id ON employees(id);");
        Result tree = database.execute("CREATE INDEX by_salary ON employees(salary) USING BPLUS_TREE;");

        assertEquals("Index by_id created successfully", hash.message());
        assertEquals("Index by_salary created successfully", tree.message());
        assertTrue(database.trace.contains("SM.createIndex.HASH"), "HASH request not forwarded");
        assertTrue(database.trace.contains("SM.createIndex.BPLUS_TREE"), "BPLUS_TREE request not forwarded");
    }

    private static void indexScan() throws Exception {
        TestDatabase database = new TestDatabase();
        database.seed();
        database.execute("CREATE INDEX by_id ON employees(id);");
        IndexCondition condition = new IndexCondition(
                IndexOperator.EQ, sqlike.qo.Literals.integer(2), Optional.empty(), true, true);
        PlanNode plan = PlanNodes.indexScan("employees", "by_id", condition);
        database.storage.scans = 0;
        database.storage.lookups = 0;
        database.storage.fetches = 0;

        Result result = database.runner.executePlan(database.session, plan);
        assertTrue(result.success(), result.message());
        assertEquals(List.of(List.of(2, 2000.0)), values(result));
        assertEquals(0, database.storage.scans);
        assertEquals(1, database.storage.lookups);
        assertEquals(1, database.storage.fetches);
    }

    private static void restartPhase(String phase, Path snapshot) {
        TestDatabase database = new TestDatabase(snapshot);
        if (phase.equals("write")) {
            database.seed();
            database.execute("BEGIN;");
            database.execute("UPDATE employees SET salary = 1.05 * salary;");
            database.execute("COMMIT;");
        } else if (phase.equals("read")) {
            assertEquals(List.of(List.of(1, 1050.0), List.of(2, 2100.0)), database.rows());
        } else {
            throw new IllegalArgumentException("Unknown restart phase: " + phase);
        }
        System.out.println("RESTART PASS " + phase + " pid=" + ProcessHandle.current().pid());
    }

    private static void runProcess(String phase, Path snapshot) throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"),
                Qp5AcceptanceTest.class.getName(), phase, snapshot.toString())
                .redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Restart process timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.exitValue() == 0 && output.contains("RESTART PASS " + phase), output);
        System.out.print(output);
    }

    private static final class TestDatabase {
        final List<String> trace = Collections.synchronizedList(new ArrayList<>());
        final StubStorage storage;
        final StubTransactions transactions;
        final StubQueryRunner runner;
        final SessionState session = new SessionState();

        TestDatabase() throws Exception {
            this(Files.createTempDirectory("qp5-test-").resolve("storage.stub"));
        }
        TestDatabase(Path snapshot) {
            storage = new StubStorage(snapshot, trace);
            transactions = new StubTransactions(storage, trace);
            if (storage.listTables().isEmpty()) {
                storage.define(new TableSchema(1, "employees", List.of(
                        new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                        new ColumnSchema("salary", DataType.FLOAT, Optional.empty()))));
            }
            runner = new StubQueryRunner(storage, transactions,
                    new QpConfiguration(0, 2, 4096, IndexType.HASH, snapshot.getParent()));
        }
        Result execute(String sql) {
            Result result = runner.execute(session, sql);
            assertTrue(result.success(), sql + ": " + result.message());
            return result;
        }
        void seed() {
            execute("INSERT INTO employees VALUES (1, 1000);");
            execute("INSERT INTO employees VALUES (2, 2000);");
        }
        List<List<Object>> rows() {
            return values(execute("SELECT * FROM employees;"));
        }
    }

    private static List<List<Object>> values(Result result) {
        return result.rows().stream()
                .map(row -> row.values().stream().map(value -> payload(value)).toList())
                .toList();
    }

    private static void assertAffected(long expected, Result result) {
        assertEquals(expected, result.affected());
        assertEquals(expected + " row(s) affected", result.message());
    }

    private static void assertBefore(List<String> trace, String first, String second) {
        assertTrue(trace.indexOf(first) >= 0 && trace.indexOf(first) < trace.indexOf(second),
                "Expected " + first + " before " + second);
    }

    private static void assertEquals(Object expected, Object actual) {
        assertTrue(Objects.equals(expected, actual), "Expected " + expected + ", actual " + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface AcceptanceCase {
        void run() throws Exception;
    }

    private static void test(String name, AcceptanceCase scenario) {
        try {
            scenario.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError failure) {
            failed++;
            System.out.println("FAIL " + name + ": " + failure);
        }
    }
}
