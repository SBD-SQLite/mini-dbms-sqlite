package sqlike.qp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import sqlike.qo.PlanNodes;
import sqlike.shared.*;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.*;
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
        test("QP-09 Access request ordering and merging", Qp5AcceptanceTest::accessOrderingAndMerging);
        test("QP-04 CHAR/VARCHAR length in UTF-8 bytes", Qp5AcceptanceTest::utf8ByteLength);
        test("QP-12 COMMIT success and rejection", Qp5AcceptanceTest::commitResults);
        test("QP-13 CREATE INDEX and confirmation", Qp5AcceptanceTest::createIndexes);
        test("QP-13 IndexScan without table scan", Qp5AcceptanceTest::indexScan);
        test("QP-05..QP-09 SELECT, WHERE, JOIN, NATURAL JOIN, ORDER BY, DELETE end-to-end", Qp5AcceptanceTest::joinSortAndDeleteEndToEnd);
        test("QP-15* CREATE TABLE, PK/FK constraints, and explicit tx rejection", Qp5AcceptanceTest::createTableAndConstraints);
        test("QP-15* DROP TABLE RESTRICT vs CASCADE", Qp5AcceptanceTest::dropTableRestrictAndCascade);
        test("QP-16* LIMIT and AS table alias (including self-join)", Qp5AcceptanceTest::limitAndTableAlias);
        test("QP-16* Subquery in WHERE (SCALAR, IN, EXISTS, correlated, 0-row NULL, >1-row error)", Qp5AcceptanceTest::subqueriesInWhere);
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
        TxId first = new TxId(database.session.getCurrentTxId().orElseThrow());
        assertTrue(begin.message().contains("ACTIVE"), begin.message());
        assertTrue(database.trace.contains("FRM.BEGIN." + first.value()), "Missing BEGIN event");

        database.execute("ABORT;");
        database.execute("BEGIN TRANSACTION;");
        TxId second = new TxId(database.session.getCurrentTxId().orElseThrow());
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

    private static void accessOrderingAndMerging() throws Exception {
        TestDatabase database = new TestDatabase();
        AccessCoordinator coordinator = new AccessCoordinator(database.transactions);
        TxId tx = database.transactions.begin(database.session.sessionId());
        ExecutionContext context = ExecutionContext.of(tx.value());

        // Requests for "zeta" (READ), "alpha" (READ), "beta" (WRITE), "alpha" (WRITE)
        PreparedStatement stmt = new PreparedStatement(List.of(
                new AccessRequest(ResourceKey.table(new TableId("zeta")), AccessMode.READ),
                new AccessRequest(ResourceKey.table(new TableId("alpha")), AccessMode.READ),
                new AccessRequest(ResourceKey.table(new TableId("beta")), AccessMode.WRITE),
                new AccessRequest(ResourceKey.table(new TableId("alpha")), AccessMode.WRITE)
        ));

        database.trace.clear();
        coordinator.acquireAll(stmt, context);

        // Verify alpha upgraded to WRITE
        assertEquals(AccessMode.WRITE, context.requireGrant(new TableId("alpha"), AccessMode.WRITE).mode());
        assertEquals(AccessMode.WRITE, context.requireGrant(new TableId("beta"), AccessMode.WRITE).mode());
        assertEquals(AccessMode.READ, context.requireGrant(new TableId("zeta"), AccessMode.READ).mode());

        // Verify exactly 3 requests (not 4) to CCM, in TableId sorted order: alpha (WRITE), beta (WRITE), zeta (READ)
        List<String> ccmAccessTrace = database.trace.stream()
                .filter(t -> t.startsWith("CCM.access."))
                .toList();
        assertEquals(List.of("CCM.access.WRITE", "CCM.access.WRITE", "CCM.access.READ"), ccmAccessTrace);
    }

    private static void utf8ByteLength() {
        Schema schema = Schema.of(List.of(
                new ColumnSchema("code", DataType.VARCHAR, Optional.of(3))
        ));

        // ASCII 3 chars = 3 bytes -> valid
        Value v1 = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("abc"));
        Row r1 = schema.validate(new Row(List.of(v1)));
        assertEquals("abc", r1.values().get(0).textValue().orElseThrow());

        // 'é' is 1 char but 2 UTF-8 bytes. "é" in VARCHAR(3) -> 2 bytes -> valid
        Value v2 = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("é"));
        Row r2 = schema.validate(new Row(List.of(v2)));
        assertEquals("é", r2.values().get(0).textValue().orElseThrow());

        // "éé" is 2 chars but 4 UTF-8 bytes -> exceeds VARCHAR(3) (3 bytes)!
        boolean threw = false;
        try {
            Value v3 = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("éé"));
            schema.validate(new Row(List.of(v3)));
        } catch (IllegalStateException e) {
            threw = e.getMessage().contains("Text too long");
        }
        assertTrue(threw, "String with UTF-8 byte size exceeding maxLength should be rejected");
    }

    private static void createIndexes() throws Exception {
        TestDatabase database = new TestDatabase();

        // 1. CREATE INDEX within explicit transaction must be rejected, without implicit commit, and transaction remains active
        database.execute("BEGIN;");
        Result rejectedInTx = database.runner.execute(database.session, "CREATE INDEX by_id_fail ON employees(id);");
        assertTrue(!rejectedInTx.success(), "CREATE INDEX should fail inside explicit transaction");
        assertTrue(rejectedInTx.message().contains("not allowed within an explicit transaction"),
                "Wrong error message: " + rejectedInTx.message());
        assertTrue(!database.trace.contains("SM.createIndex.by_id_fail"), "SM index created despite rejection");
        assertTrue(database.session.getCurrentTxId().isPresent(), "Transaction was aborted or cleared");
        database.execute("ABORT;");

        // 2. Normal CREATE INDEX outside explicit transaction
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

    private static void joinSortAndDeleteEndToEnd() throws Exception {
        TestDatabase database = new TestDatabase();
        database.storage.define(new TableSchema("2", "departments", List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("dept_name", DataType.VARCHAR, Optional.of(32)))));
        database.execute("INSERT INTO employees VALUES (1, 3000);");
        database.execute("INSERT INTO employees VALUES (2, 1000);");
        database.execute("INSERT INTO employees VALUES (3, 2000);");
        database.execute("INSERT INTO departments VALUES (1, 'Engineering');");
        database.execute("INSERT INTO departments VALUES (2, 'Finance');");

        // JOIN ... ON with table.column prefix and ORDER BY ASC
        Result joined = database.execute(
                "SELECT employees.id, departments.dept_name, employees.salary "
                + "FROM employees JOIN departments ON employees.id = departments.id "
                + "ORDER BY employees.salary ASC;");
        assertEquals(
                List.of(List.of(2, "Finance", 1000.0), List.of(1, "Engineering", 3000.0)),
                values(joined));

        // NATURAL JOIN on common column id with ORDER BY DESC
        Result natural = database.execute(
                "SELECT id, dept_name, salary FROM employees NATURAL JOIN departments ORDER BY id DESC;");
        assertEquals(
                List.of(List.of(2, "Finance", 1000.0), List.of(1, "Engineering", 3000.0)),
                values(natural));

        // DELETE with WHERE and rollback on ABORT
        database.execute("BEGIN;");
        Result deleted = database.execute("DELETE FROM employees WHERE id = 2;");
        assertAffected(1, deleted);
        assertEquals(List.of(List.of(1, 3000.0), List.of(3, 2000.0)), database.rows());
        database.execute("ABORT;");
        assertEquals(List.of(List.of(1, 3000.0), List.of(2, 1000.0), List.of(3, 2000.0)), database.rows());
    }

    private static void createTableAndConstraints() throws Exception {
        TestDatabase database = new TestDatabase();

        // 1. CREATE TABLE inside explicit transaction must be rejected without aborting transaction
        database.execute("BEGIN;");
        Result inTx = database.runner.execute(database.session,
                "CREATE TABLE depts_fail (id INTEGER PRIMARY KEY, title VARCHAR(16));");
        assertTrue(!inTx.success(), "CREATE TABLE must fail inside explicit transaction");
        assertTrue(inTx.message().contains("not allowed within an explicit transaction"), inTx.message());
        assertTrue(database.session.getCurrentTxId().isPresent(), "Active transaction must remain open");
        database.execute("ABORT;");

        // 2. Auto-commit CREATE TABLE with PRIMARY KEY and FOREIGN KEY
        Result createdParent = database.execute(
                "CREATE TABLE departments (id INTEGER, title VARCHAR(20), PRIMARY KEY (id));");
        assertEquals("Table departments created successfully", createdParent.message());

        Result createdChild = database.execute(
                "CREATE TABLE staff (id INTEGER, dept_id INTEGER, code CHAR(3), "
                + "PRIMARY KEY (id), FOREIGN KEY (dept_id) REFERENCES departments(id));");
        assertEquals("Table staff created successfully", createdChild.message());

        // Duplicate table creation must fail
        Result dupTable = database.runner.execute(database.session,
                "CREATE TABLE departments (id INTEGER PRIMARY KEY);");
        assertTrue(!dupTable.success(), "Duplicate CREATE TABLE must be rejected");

        // 3. PK uniqueness & FK parent existence enforcement on INSERT/UPDATE/DELETE
        database.execute("INSERT INTO departments VALUES (10, 'R&D');");
        database.execute("INSERT INTO departments VALUES (20, 'Sales');");

        Result dupPk = database.runner.execute(database.session,
                "INSERT INTO departments VALUES (10, 'Duplicate');");
        assertTrue(!dupPk.success(), "Duplicate PRIMARY KEY on INSERT must be rejected");

        Result missingFkParent = database.runner.execute(database.session,
                "INSERT INTO staff VALUES (100, 99, 'ENG');");
        assertTrue(!missingFkParent.success(), "INSERT with non-existent FK parent key must be rejected");

        database.execute("INSERT INTO staff VALUES (100, 10, 'ENG');");

        Result deleteReferencedParent = database.runner.execute(database.session,
                "DELETE FROM departments WHERE id = 10;");
        assertTrue(!deleteReferencedParent.success(), "DELETE of parent row referenced by child FK must be rejected");

        // Unreferenced parent row (id=20) can be deleted
        assertAffected(1, database.execute("DELETE FROM departments WHERE id = 20;"));
    }

    private static void dropTableRestrictAndCascade() throws Exception {
        TestDatabase database = new TestDatabase();
        database.execute("CREATE TABLE departments (id INTEGER PRIMARY KEY, title VARCHAR(20));");
        database.execute("CREATE TABLE staff (id INTEGER PRIMARY KEY, dept_id INTEGER, "
                + "FOREIGN KEY (dept_id) REFERENCES departments(id));");
        database.execute("INSERT INTO departments VALUES (1, 'Core');");
        database.execute("INSERT INTO staff VALUES (50, 1);");

        // 1. DROP TABLE inside explicit transaction must be rejected
        database.execute("BEGIN;");
        Result inTx = database.runner.execute(database.session, "DROP TABLE staff;");
        assertTrue(!inTx.success(), "DROP TABLE must fail inside explicit transaction");
        assertTrue(database.session.getCurrentTxId().isPresent(), "Active transaction must remain open");
        database.execute("ABORT;");

        // 2. DROP TABLE RESTRICT (default) rejects dropping parent table referenced by staff's FK
        Result restricted = database.runner.execute(database.session, "DROP TABLE departments RESTRICT;");
        assertTrue(!restricted.success(), "DROP TABLE RESTRICT must fail when referenced by another table's FK");
        assertTrue(database.storage.listTables().contains("departments"), "departments must remain after RESTRICT failure");

        // 3. DROP TABLE CASCADE drops parent table and removes referencing ForeignKeySpec from child table without deleting child rows
        Result cascaded = database.execute("DROP TABLE departments CASCADE;");
        assertEquals("Table departments dropped successfully", cascaded.message());
        assertTrue(!database.storage.listTables().contains("departments"), "departments must be removed");
        assertTrue(database.storage.listTables().contains("staff"), "child table staff must remain");
        assertEquals(List.of(), database.storage.getSchema("staff").foreignKeys());
        assertEquals(List.of(List.of(50, 1)), values(database.execute("SELECT * FROM staff;")));
    }

    private static void limitAndTableAlias() throws Exception {
        TestDatabase database = new TestDatabase();
        database.execute("INSERT INTO employees VALUES (1, 1000);");
        database.execute("INSERT INTO employees VALUES (2, 3000);");
        database.execute("INSERT INTO employees VALUES (3, 2000);");

        // LIMIT n after ORDER BY
        Result top2 = database.execute("SELECT id, salary FROM employees ORDER BY salary DESC LIMIT 2;");
        assertEquals(List.of(List.of(2, 3000.0), List.of(3, 2000.0)), values(top2));

        // LIMIT 0 returns 0 rows and preserves schema header
        Result limit0 = database.execute("SELECT id, salary FROM employees LIMIT 0;");
        assertEquals(List.of(), values(limit0));
        assertEquals(2, limit0.schema().columns().size());

        // Table alias AS and self-join
        Result aliased = database.execute("SELECT e.id, e.salary FROM employees AS e WHERE e.id = 2;");
        assertEquals(List.of(List.of(2, 3000.0)), values(aliased));

        // Original table name is not valid as qualifier when aliased
        Result invalidOrigQualifier = database.runner.execute(
                database.session, "SELECT employees.id FROM employees AS e;");
        assertTrue(!invalidOrigQualifier.success(), "Original table name qualifier must fail when table is aliased");

        // Self-join using two distinct aliases on the same physical table
        Result selfJoin = database.execute(
                "SELECT e1.id, e2.id FROM employees AS e1 JOIN employees AS e2 ON e1.salary < e2.salary "
                + "ORDER BY e1.id ASC;");
        assertEquals(List.of(List.of(1, 3000.0 > 1000.0 ? 2 : 0), List.of(1, 3), List.of(3, 2)), values(selfJoin));
    }

    private static void subqueriesInWhere() throws Exception {
        TestDatabase database = new TestDatabase();
        database.execute("CREATE TABLE projects (id INTEGER PRIMARY KEY, lead_id INTEGER, budget FLOAT);");
        database.execute("INSERT INTO employees VALUES (1, 1000);");
        database.execute("INSERT INTO employees VALUES (2, 2500);");
        database.execute("INSERT INTO employees VALUES (3, 4000);");
        database.execute("INSERT INTO projects VALUES (10, 2, 50000);");
        database.execute("INSERT INTO projects VALUES (20, 3, 80000);");

        // 1. SCALAR subquery returning 1 row
        Result scalarOne = database.execute(
                "SELECT id FROM employees WHERE salary > (SELECT salary FROM employees WHERE id = 2);");
        assertEquals(List.of(List.of(3)), values(scalarOne));

        // 2. SCALAR subquery returning 0 rows -> evaluates to SQL NULL -> comparison is UNKNOWN (false in WHERE)
        Result scalarZero = database.execute(
                "SELECT id FROM employees WHERE salary = (SELECT salary FROM employees WHERE id = 999);");
        assertEquals(List.of(), values(scalarZero));

        // 3. SCALAR subquery returning >1 row -> runtime error (INVALID_VALUE)
        Result scalarMulti = database.runner.execute(
                database.session,
                "SELECT id FROM employees WHERE salary = (SELECT salary FROM employees);");
        assertTrue(!scalarMulti.success(), "SCALAR subquery returning >1 row must fail");
        assertTrue(scalarMulti.message().contains("more than one row"), scalarMulti.message());

        // 4. IN subquery
        Result inSub = database.execute(
                "SELECT id FROM employees WHERE id IN (SELECT lead_id FROM projects) ORDER BY id ASC;");
        assertEquals(List.of(List.of(2), List.of(3)), values(inSub));

        // 5. EXISTS correlated subquery referencing outer alias e
        Result existsCorrelated = database.execute(
                "SELECT e.id FROM employees AS e "
                + "WHERE EXISTS (SELECT p.id FROM projects AS p WHERE p.lead_id = e.id AND p.budget > 60000);");
        assertEquals(List.of(List.of(3)), values(existsCorrelated));
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
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(
                javaBin,
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
                storage.define(new TableSchema("1", "employees", List.of(
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
