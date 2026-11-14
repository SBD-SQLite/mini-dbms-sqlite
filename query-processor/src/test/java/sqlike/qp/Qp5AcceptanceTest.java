package sqlike.qp;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import sqlike.contract.IConcurrencyControlManager;
import sqlike.qo.PlanNodes;
import sqlike.qo.QoException;
import sqlike.shared.*;
import sqlike.qp.port.ConcurrencyAdapter;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.*;
import sqlike.qp.stub.StubQueryRunner.Result;
import static sqlike.qp.port.QpContracts.payload;

/** QP5 acceptance and integration checks with stub storage and transaction services. */
public final class Qp5AcceptanceTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--qo-integration")) {
            runQoIntegrationChecks();
            runAccessChecks();
            reportResults();
            return;
        }
        if (args.length == 2) {
            restartPhase(args[0], Path.of(args[1]));
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Expected no arguments, --qo-integration, or a restart phase and snapshot");
        System.out.println("QP acceptance checks with stub SM and CCM/FRM.");
        runQoIntegrationChecks();
        runAccessChecks();
        test("QP-10 INSERT and QP-12 ABORT", Qp5AcceptanceTest::insertAndAbort);
        test("QP-11 SET, arithmetic, WHERE, and counts", Qp5AcceptanceTest::updateRows);
        test("QP-11 committed UPDATE survives restart", Qp5AcceptanceTest::updateSurvivesRestart);
        test("QP-12 BEGIN forms, unique IDs, and event", Qp5AcceptanceTest::beginTransactions);
        test("QP-12 auto-commit", Qp5AcceptanceTest::autoCommit);
        test("QP-12 access permission and WAIT", Qp5AcceptanceTest::accessAndWait);
        test("QP-12 foreign-key validation requires a read grant", Qp5AcceptanceTest::foreignKeyReadAccess);
        test("QP-12 CCM adapter reports failed ABORT and preserves the session", Qp5AcceptanceTest::adapterAbortStatus);
        test("QP-09 Access request ordering and merging", Qp5AcceptanceTest::accessOrderingAndMerging);
        test("QP-04 CHAR/VARCHAR length in Unicode code points", Qp5AcceptanceTest::textCodePointLength);
        test("QP-10/QP-11 Unicode text survives INSERT and UPDATE", Qp5AcceptanceTest::unicodeTextDml);
        test("QP-10 INSERT stops on missing or failed constraint metadata", () -> metadataFailures("INSERT"));
        test("QP-11 UPDATE stops on missing or failed constraint metadata", () -> metadataFailures("UPDATE"));
        test("QP-07 DELETE stops on missing or failed constraint metadata", () -> metadataFailures("DELETE"));
        test("QP-12 COMMIT success and rejection", Qp5AcceptanceTest::commitResults);
        test("QP-12 unknown COMMIT outcome never triggers rollback", Qp5AcceptanceTest::commitOutcomeUnknown);
        test("QP-13 CREATE INDEX and confirmation", Qp5AcceptanceTest::createIndexes);
        test("QP-13 IndexScan without table scan", Qp5AcceptanceTest::indexScan);
        test("QP-05..QP-09 SELECT, WHERE, JOIN, NATURAL JOIN, ORDER BY, DELETE end-to-end", Qp5AcceptanceTest::joinSortAndDeleteEndToEnd);
        test("QP-15* CREATE TABLE, PK/FK constraints, and explicit tx rejection", Qp5AcceptanceTest::createTableAndConstraints);
        test("QP-15* DROP TABLE RESTRICT vs CASCADE", Qp5AcceptanceTest::dropTableRestrictAndCascade);
        test("QP-16* LIMIT and AS table alias (including self-join)", Qp5AcceptanceTest::limitAndTableAlias);
        test("QP-16* Subquery in WHERE (SCALAR, IN, EXISTS, correlated, 0-row NULL, >1-row error)", Qp5AcceptanceTest::subqueriesInWhere);
        test("QP-01/QP-14 TCP QueryServer, CliClient, QpBootstrap, and disconnect auto-abort", Qp5AcceptanceTest::tcpServerMultiClientAndDisconnectAbort);
        reportResults();
    }

    private static void runQoIntegrationChecks() {
        test("QP-05/QP-08/QP-09/QP-13 QO layouts and index access", Qp5AcceptanceTest::qoOperatorLayouts);
        test("QP-03 QO plans execute through the public QP facade", Qp5AcceptanceTest::qoCorePipeline);
        test("QP-01 QO errors retain code, component, position, and transaction", Qp5AcceptanceTest::qoErrorsPreserved);
    }

    private static void reportResults() {
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) throw new AssertionError("Acceptance checks failed");
    }

    private static void qoOperatorLayouts() throws Exception {
        var runtime = StubQpRuntime.open(Files.createTempDirectory("qp-qo-layouts-"), QpConfiguration.defaults());
        runtime.storage.define(new TableSchema("3", "grades", List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("name", DataType.VARCHAR, Optional.of(30)),
                new ColumnSchema("score", DataType.INTEGER, Optional.empty()))));
        var loader = new SessionState();
        assertAffected(1, runtime.runner.execute(loader, "INSERT INTO grades VALUES (1, 'Ani', 9);"));
        assertAffected(1, runtime.runner.execute(loader, "INSERT INTO grades VALUES (2, 'Other', 8);"));
        runtime.recover();
        var processor = runtime.processor; var session = processor.openSession();
        try {
            var natural = processor.execute("SELECT * FROM student NATURAL JOIN grades ORDER BY student.id;", session);
            assertEquals(ResultKind.ROWS, natural.kind());
            assertEquals(List.of("id", "name", "gpa", "score"), natural.columns().stream().map(ResultColumn::name).toList());
            assertEquals(List.of(List.of(1, "Ani", 3.5, 9)), natural.rows().stream()
                    .map(row -> row.stream().map(value -> payload(value)).toList()).toList());
            var hiddenSort = processor.execute("SELECT name FROM student ORDER BY gpa DESC;", session);
            assertEquals(ResultKind.ROWS, hiddenSort.kind());
            assertEquals(List.of("Cici", "Ani", "Budi"), hiddenSort.rows().stream()
                    .map(row -> row.get(0).textValue().orElseThrow()).toList());
            assertEquals(ResultKind.AFFECTED_ROWS, processor.execute("CREATE INDEX idx_layout_id ON student (id);", session).kind());
            var begin = processor.execute("BEGIN;", session);
            long tx = begin.transaction().orElseThrow().txId();
            var index = sqlike.qo.PlanNodes.indexScan("student", "idx_layout_id",
                    new IndexCondition(IndexOperator.EQ, sqlike.qo.Literals.integer(2), Optional.empty(), true, true));
            var indexed = processor.executePlan(new QueryPlan(index, 0), tx);
            assertEquals(ResultKind.ROWS, indexed.kind());
            assertEquals(List.of(List.of(2, "Budi", 3.2)), indexed.rows().stream()
                    .map(row -> row.stream().map(value -> payload(value)).toList()).toList());
            assertEquals(ResultKind.TRANSACTION, processor.execute("ABORT;", session).kind());
        } finally { processor.closeSession(session.sessionId()); processor.shutdown(); }
    }

    private static void qoCorePipeline() throws Exception {
        StubQpRuntime runtime = StubQpRuntime.open(Files.createTempDirectory("qp-qo-core-"), QpConfiguration.defaults());
        runtime.recover();
        var processor = runtime.processor;
        var session = processor.openSession();
        try {
            var joined = processor.execute("SELECT student.name, enrollment.course FROM student JOIN enrollment ON student.id = enrollment.student_id WHERE enrollment.course = 'IF3140' ORDER BY student.name DESC;", session);
            assertEquals(ResultKind.ROWS, joined.kind());
            assertEquals(List.of("name", "course"), joined.columns().stream().map(ResultColumn::name).toList());
            assertEquals(List.of(List.of("Budi", "IF3140"), List.of("Ani", "IF3140")),
                    joined.rows().stream().map(row -> row.stream().map(value -> payload(value)).toList()).toList());

            var begin = processor.execute("BEGIN;", session);
            assertEquals(ResultKind.TRANSACTION, begin.kind());
            var updated = processor.execute("UPDATE student SET gpa = gpa + 0.5 WHERE id = 1;", session);
            assertEquals(ResultKind.AFFECTED_ROWS, updated.kind());
            assertEquals(1L, updated.affectedRows());
            var visible = processor.execute("SELECT gpa FROM student WHERE id = 1;", session);
            assertEquals(4.0, visible.rows().get(0).get(0).floatValue().orElseThrow());

            var parsed = runtime.runner.optimizer.parseQuery("SELECT name FROM student ORDER BY name;");
            var planned = processor.executePlan(runtime.runner.optimizer.optimizeQuery(parsed), begin.transaction().orElseThrow().txId());
            assertEquals(ResultKind.ROWS, planned.kind());
            assertEquals(List.of("Ani", "Budi", "Cici"), planned.rows().stream().map(row -> row.get(0).textValue().orElseThrow()).toList());
            assertEquals(ResultKind.TRANSACTION, processor.execute("ABORT;", session).kind());
            var restored = processor.execute("SELECT gpa FROM student WHERE id = 1;", session);
            assertEquals(3.5, restored.rows().get(0).get(0).floatValue().orElseThrow());
        } finally {
            processor.closeSession(session.sessionId());
            processor.shutdown();
        }
    }

    private static void qoErrorsPreserved() throws Exception {
        StubQpRuntime runtime = StubQpRuntime.open(Files.createTempDirectory("qp-qo-errors-"), QpConfiguration.defaults());
        runtime.recover();
        var processor = runtime.processor;
        var session = processor.openSession();
        try {
            for (var scenario : List.of(
                    Map.entry("SELEC * FROM student;", ErrorCode.SYNTAX_ERROR),
                    Map.entry("SELECT missing FROM student;", ErrorCode.SEMANTIC_ERROR),
                    Map.entry("SELECT DISTINCT * FROM student;", ErrorCode.UNSUPPORTED_OPERATION))) {
                ErrorInfo expected;
                try {
                    runtime.runner.optimizer.optimizeQuery(runtime.runner.optimizer.parseQuery(scenario.getKey()));
                    throw new AssertionError("Invalid SQL was accepted by QO");
                } catch (QoException error) {
                    expected = error.getInfo();
                }
                assertEquals(scenario.getValue(), expected.code());
                assertEquals(ComponentType.QO, expected.component());
                if (scenario.getValue() == ErrorCode.SYNTAX_ERROR) assertEquals(Optional.of(0), expected.position());

                var begin = processor.execute("BEGIN;", session);
                long txId = begin.transaction().orElseThrow().txId();
                var result = processor.execute(scenario.getKey(), session);
                assertEquals(ResultKind.ERROR, result.kind());
                assertEquals(expected, result.error().orElseThrow());
                assertTrue(ResultPrinter.format(result).contains("ERROR [QO " + expected.code() + "]"), "CLI lost QO error details");

                List<ExecutionSummary> summaries = new ArrayList<>();
                var sink = new ResultSink() {
                    @Override public void onSchema(Schema schema) {throw new AssertionError("Failed query emitted a schema");}
                    @Override public void onRow(Row row) {throw new AssertionError("Failed query emitted a row");}
                    @Override public void onFinish(ExecutionSummary summary) {summaries.add(summary);}
                };
                var summary = processor.execute(new SessionId(UUID.fromString(session.sessionId())), scenario.getKey(), sink);
                assertTrue(!summary.success(), "Streaming facade accepted invalid SQL");
                assertEquals(expected, summary.getErrorInfo().orElseThrow());
                assertEquals(List.of(summary), summaries);
                assertEquals(ResultKind.ROWS, processor.execute("SELECT * FROM student;", session).kind());
                var commit = processor.execute("COMMIT;", session);
                assertEquals(ResultKind.TRANSACTION, commit.kind());
                assertEquals(txId, commit.transaction().orElseThrow().txId());
                assertEquals(TransactionStatus.COMMITTED, commit.transaction().orElseThrow().status());
            }
        } finally {
            processor.closeSession(session.sessionId());
            processor.shutdown();
        }
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

    private static void foreignKeyReadAccess() throws Exception {
        TestDatabase database = new TestDatabase();
        var id = new ColumnSchema("id", DataType.INTEGER, Optional.empty());
        database.storage.define(new TableSchema("2", "departments", List.of(id), List.of("id"), List.of()));
        database.storage.define(new TableSchema("3", "staff", List.of(id), List.of(),
                List.of(new ForeignKeySpec(List.of("id"), "departments", List.of("id")))));
        database.execute("INSERT INTO departments VALUES (10);");

        database.trace.clear();
        database.transactions.scriptAccess(AccessStatus.GRANTED, AccessStatus.WAIT, AccessStatus.GRANTED);
        database.transactions.releaseWait = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Result> insertion = worker.submit(() -> database.execute("INSERT INTO staff VALUES (10);"));
            assertTrue(database.transactions.enteredWait.await(3, TimeUnit.SECONDS), "No CCM wait for the foreign-key read");
            assertTrue(!database.trace.contains("SM.scan"), "Foreign key was read before the grant");
            database.transactions.releaseWait.countDown();
            assertAffected(1, insertion.get(5, TimeUnit.SECONDS));
            assertBefore(database.trace, "CCM.wait", "SM.scan");
        } finally {
            database.transactions.releaseWait.countDown();
            worker.shutdownNow();
        }

        database.trace.clear();
        database.transactions.scriptAccess(AccessStatus.GRANTED, AccessStatus.WAIT, AccessStatus.ABORTED);
        Result aborted = database.runner.execute(database.session, "INSERT INTO staff VALUES (10);");
        assertTrue(!aborted.success() && aborted.message().contains("CCM aborted"), aborted.message());
        assertTrue(!database.trace.contains("SM.scan") && !database.trace.contains("SM.reserve"),
                "Aborted foreign-key validation reached storage");
    }

    private static void commitOutcomeUnknown() {
        var partial = new TransactionResponse(1, 0, TransactionStatus.PARTIALLY_COMMITTED, Optional.empty());
        List<Supplier<TransactionResponse>> responses = List.of(() -> partial, () -> null, () -> {
            throw new IllegalStateException("Lost COMMIT response");
        });
        for (var response : responses) {
            var commits = new AtomicInteger();
            var adapter = adapterWith((proxy, method, args) -> {
                assertEquals("commit", method.getName());
                assertEquals(1, commits.incrementAndGet());
                return response.get();
            });
            var coordinator = new TransactionCoordinator(adapter, QpConfiguration.defaults());
            var session = new SessionState();
            var tx = new TxId(1);
            adapter.registerExternalTransaction(tx.value());
            session.attachTransaction(tx, false);
            ExecutionContext context = coordinator.ensureTransaction(session);

            try {
                coordinator.finishImplicit(session, context);
                throw new AssertionError("Unknown commit reported success");
            } catch (IllegalStateException failure) {
                coordinator.handleRuntimeFailure(session, context, new DbmsError(failure.getMessage()));
            }
            assertEquals(1, commits.get());
            assertEquals(TransactionState.PARTIALLY_COMMITTED, adapter.getState(tx));
            assertEquals(TransactionState.PARTIALLY_COMMITTED, coordinator.abort(session, "User ABORT"));
            assertEquals(TransactionState.PARTIALLY_COMMITTED, coordinator.commit(session));
            try {
                coordinator.closeSession(session);
                throw new AssertionError("Session closed with unknown commit outcome");
            } catch (IllegalStateException failure) {
                assertTrue(failure.getMessage().contains("Commit outcome unknown"), failure.getMessage());
            }
            assertEquals(Optional.of(tx), session.currentTxId());
        }
    }

    private static void runAccessChecks() {
        test("QP runtime rejects inherited placeholder capabilities before recovery", Qp5AcceptanceTest::runtimeCapabilities);
        test("QP runtime validates recovery and wires one processor", Qp5AcceptanceTest::runtimeRecovery);
        test("QP runtime preserves failed shutdown state", Qp5AcceptanceTest::runtimeShutdown);
        test("QP WAL executePlan shares transaction change identity", Qp5AcceptanceTest::walExternalPlans);
        test("QP WAL missing or invalid LSN never becomes a permit", Qp5AcceptanceTest::walPermitBoundaries);
        test("QP WAL unfinished apply blocks commit and rollback", Qp5AcceptanceTest::walApplyGuard);
        test("QP WAL schema writes log before apply with the same LSN", Qp5AcceptanceTest::walSchemaWrites);
        test("QP WAL schema failures restore prior state", Qp5AcceptanceTest::walSchemaFailures);
        test("QP WAL cleanup preserves the primary failure", Qp5AcceptanceTest::walCleanupFailures);
        test("QP-12 CCM adapter blocks without retrying writes", Qp5AcceptanceTest::adapterWriteWait);
        test("QP-12 strict grants and cancellation", Qp5AcceptanceTest::strictGrants);
        test("QP-03 catalog permission precedes optimization", Qp5AcceptanceTest::catalogBeforeOptimization);
        test("QP-12 adapter catalog, abort, and cancelled grants", Qp5AcceptanceTest::adapterAccessBoundary);
        test("QP-02 failed disconnect rollback stays registered", Qp5AcceptanceTest::failedDisconnectRollback);
    }

    private static <T> T runtimeProxy(Class<T> contract, InvocationHandler handler) {
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, handler));
    }

    private static void runtimeCapabilities() throws Exception {
        TestDatabase db = new TestDatabase();
        var ccm = runtimeProxy(IConcurrencyControlManager.class, (proxy, method, args) -> { throw new AssertionError("CCM called before capability validation"); });
        var frm = runtimeProxy(sqlike.contract.IFailureRecoveryManager.class, (proxy, method, args) -> { throw new AssertionError("Recovery called with incompatible providers"); });
        var processor = new QueryProcessor(db.runner.optimizer, db.storage, ccm, frm);
        try { processor.initialize(); throw new AssertionError("Provider default accepted"); }
        catch (UnsupportedOperationException expected) { assertTrue(expected.getMessage().contains("restoreSchema"), expected.getMessage()); }
        assertEquals(SystemState.FAILED, processor.getState());
    }

    private static void runtimeRecovery() throws Exception {
        TestDatabase db = new TestDatabase();
        for (int scenario : List.of(0, 1, 2)) {
            var events = new ArrayList<String>();
            var storage = runtimeProxy(sqlike.contract.IStorageManager.class, (proxy, method, args) -> { throw new AssertionError("Startup touched storage: " + method.getName()); });
            var ccm = runtimeProxy(IConcurrencyControlManager.class, (proxy, method, args) -> {
                assertEquals("initAfterRecovery", method.getName()); assertEquals(7L, args[0]); events.add("init"); return null;
            });
            var frm = runtimeProxy(sqlike.contract.IFailureRecoveryManager.class, (proxy, method, args) -> {
                assertEquals("recover", method.getName()); events.add("recover");
                return scenario == 0 ? null : new RecoveryResult(7, 8, 9, Optional.of(scenario == 1 ? 10L : 3L));
            });
            var config = new QpConfiguration(0, 2, 4096, IndexType.HASH, Files.createTempDirectory("qp-runtime-"));
            var bootstrap = QpBootstrap.forComponents(db.runner.optimizer, storage, ccm, frm, config);
            if (scenario < 2) {
                try { bootstrap.start(); throw new AssertionError("Invalid recovery accepted"); }
                catch (QpException expected) { assertEquals(ErrorCode.RECOVERY_FAILURE, expected.getErrorInfo().code()); }
                assertEquals(SystemState.FAILED, bootstrap.getState()); assertEquals(List.of("recover"), events);
            } else {
                try { bootstrap.start(); bootstrap.start(); assertEquals(SystemState.READY, bootstrap.getState());
                    assertEquals(List.of("recover", "init"), events);
                } finally { bootstrap.stop(); }
                assertEquals(SystemState.STOPPED, bootstrap.getState());
            }
        }
        var other = StubQpRuntime.open(Files.createTempDirectory("qp-other-"), QpConfiguration.defaults());
        var first = StubQpRuntime.open(Files.createTempDirectory("qp-first-"), QpConfiguration.defaults());
        try { new QpBootstrap(first.processor, new QueryServer(other.processor, QpConfiguration.defaults())); throw new AssertionError("Mismatched processors accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void runtimeShutdown() throws Exception {
        var config = new QpConfiguration(0, 2, 4096, IndexType.HASH, Files.createTempDirectory("qp-stop-"));
        var runtime = StubQpRuntime.open(config.temporaryDirectory(), config); runtime.recover();
        var bootstrap = new QpBootstrap(runtime.processor, new QueryServer(runtime.processor, config));
        bootstrap.start(); var session = runtime.processor.openSession();
        runtime.processor.execute("BEGIN;", session);
        runtime.transactions.nextCommit = TransactionState.PARTIALLY_COMMITTED;
        runtime.processor.execute("COMMIT;", session);
        try { bootstrap.stop(); throw new AssertionError("Failed shutdown reported success"); }
        catch (IllegalStateException expected) { }
        assertEquals(SystemState.FAILED, bootstrap.getState());
        assertEquals(SystemState.FAILED, runtime.processor.getState());
    }

    private static void walExternalPlans() throws Exception {
        var runtime = StubQpRuntime.open(Files.createTempDirectory("qp-wal-plans-"), QpConfiguration.defaults());
        runtime.recover(); var processor = runtime.processor; var session = processor.openSession();
        try {
            var begin = processor.execute("BEGIN;", session);
            long tx = begin.transaction().orElseThrow().txId();
            var ddl = PlanNodes.createIndex(new IndexMetadata("idx_external", "student", "id", IndexType.HASH));
            assertEquals(ResultKind.ERROR, processor.executePlan(new QueryPlan(ddl, 0), tx).kind());
            for (int id : List.of(4, 5)) {
                var plan = PlanNodes.insert(new MutationSpec("student", List.of(sqlike.qo.Literals.integer(id),
                        new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("Added")),
                        new Value(DataType.FLOAT, Optional.empty(), Optional.of(3.0), Optional.empty())), Optional.empty(), Optional.empty()));
                assertEquals(ResultKind.AFFECTED_ROWS, processor.executePlan(new QueryPlan(plan, 0), tx).kind());
            }
            assertEquals(2L, processor.execute("DELETE FROM student WHERE id >= 4;", session).affectedRows());
            assertEquals(ResultKind.TRANSACTION, processor.execute("ABORT;", session).kind());
            assertEquals(3, processor.execute("SELECT * FROM student;", session).rows().size());
        } finally { processor.closeSession(session.sessionId()); }
    }

    private static void walApplyGuard() {
        for (String failing : List.of("beginApply", "endApply")) {
            var calls = new ArrayList<String>();
            var adapter = adapterWith((proxy, method, args) -> {
                calls.add(method.getName());
                if (method.getName().equals(failing)) throw new IllegalStateException("Apply response lost");
                return switch (method.getName()) {
                    case "requestWrite" -> new AccessResponse(AccessStatus.GRANTED, Optional.of(42L), Optional.empty(), Optional.empty());
                    case "beginApply" -> true;
                    default -> throw new AssertionError("Unexpected CCM call: " + method.getName());
                };
            });
            var tx = new TxId(1); adapter.registerExternalTransaction(1);
            var change = new DataChange(ChangeOperation.DELETE, "employees", new RID(new PageId("employees", 0), 0),
                    new RecordImage(true, List.of()), new RecordImage(false, List.of()));
            var permit = adapter.authorizeChange(new ChangeRecord(new ChangeId(tx, 1), tx, change));
            try {
                try (var lease = adapter.acquireApplyLease(permit)) { adapter.confirmApplied(permit.changeId()); }
                throw new AssertionError("Apply failure was ignored");
            } catch (IllegalStateException expected) { }
            assertEquals(TransactionState.FAILED, adapter.getState(tx));
            try { adapter.commit(tx); throw new AssertionError("Committed unfinished apply"); } catch (IllegalStateException expected) { }
            try { adapter.abort(tx, "test"); throw new AssertionError("Rolled back unfinished apply"); } catch (IllegalStateException expected) { }
            assertTrue(!calls.contains("commit") && !calls.contains("abort"), "Uncertain apply reached finalization");
        }
    }

    private static void walSchemaWrites() throws Exception {
        TestDatabase db = new TestDatabase(); db.seed(); db.trace.clear();
        db.execute("CREATE INDEX idx_wal ON employees (id);");
        var record = db.transactions.lastSchemaChange;
        assertEquals(record.value().before().get(0).schema(), record.value().after().get(0).schema());
        assertEquals(0, record.value().before().get(0).indexes().size());
        assertEquals(1, record.value().after().get(0).indexes().size());
        assertTrue(record.value().before().get(0).snapshotId().isEmpty(), "Index change captured a data snapshot");
        assertBefore(db.trace, "FRM.SCHEMA_CHANGE", "CCM.lease");
        assertBefore(db.trace, "CCM.lease", "SM.createIndex.HASH");
        assertBefore(db.trace, "SM.createIndex.HASH", "CCM.confirm");
        assertBefore(db.trace, "CCM.confirm", "CCM.lease.close");
        assertBefore(db.trace, "CCM.lease.close", "CCM.commit");
        assertEquals(db.trace.stream().filter(e -> e.startsWith("FRM.LSN.")).map(e -> e.substring(8)).toList(),
                db.trace.stream().filter(e -> e.startsWith("SM.LSN.")).map(e -> e.substring(7)).toList());
        assertTrue(!db.trace.contains("FRM.CHANGE"), "Index used a fake record change");
        db.trace.clear(); db.execute("INSERT INTO employees VALUES (3, 3000);");
        assertEquals(db.trace.stream().filter(e -> e.startsWith("FRM.LSN.")).map(e -> e.substring(8)).toList(),
                db.trace.stream().filter(e -> e.startsWith("SM.LSN.")).map(e -> e.substring(7)).toList());
        assertEquals(0, db.storage.reservationCount());
    }

    private static void walSchemaFailures() throws Exception {
        for (String operation : List.of("CREATE", "DROP", "INDEX")) {
            for (boolean walFailure : List.of(true, false)) {
                TestDatabase db = new TestDatabase(); db.seed();
                db.runner.transactions.ensureTransaction(db.session);
                db.transactions.failLog = walFailure;
                db.storage.failSchemaApply = !walFailure;
                PlanNode plan = switch (operation) {
                    case "CREATE" -> PlanNodes.createTable(new CreateTableSpec("created", List.of(
                            new ColumnSchema("id", DataType.INTEGER, Optional.empty())), List.of("id"), List.of()));
                    case "DROP" -> PlanNodes.dropTable(new DropTableSpec("employees", DropBehavior.RESTRICT));
                    default -> PlanNodes.createIndex(new IndexMetadata("idx_failure", "employees", "id", IndexType.HASH));
                };
                db.trace.clear();
                var failed = db.runner.executePlan(db.session, plan);
                assertTrue(!failed.success(), "Schema failure reported success");
                assertEquals(List.of("employees"), db.storage.listTables());
                assertTrue(db.storage.getIndexes("employees").isEmpty(), "Failed index remained visible");
                assertEquals(List.of(List.of(1, 1000.0), List.of(2, 2000.0)), db.rows());
                assertTrue(db.session.currentTxId().isEmpty(), "Completed rollback kept an active transaction");
                if (walFailure) assertTrue(db.trace.stream().noneMatch(e -> e.startsWith("SM.LSN.")), "WAL failure reached apply");
                else assertBefore(db.trace, "CCM.lease.close", "FRM.rollback");
                if (operation.equals("DROP")) {
                    String snapshot = db.transactions.lastSchemaChange.value().before().get(0).snapshotId().orElseThrow();
                    assertTrue(db.storage.hasSnapshot(snapshot), "DROP logged an unknown snapshot");
                }
            }
        }
    }

    private static void walCleanupFailures() throws Exception {
        var primary = new IllegalStateException("Primary apply failure");
        var cleanup = new IllegalStateException("Cleanup failure");
        CommandExecutor command = new AbstractCommandExecutor() {
            @Override protected long doExecute(ExecutionContext context) { throw primary; }
            @Override protected void doClose() { throw cleanup; }
        };
        try { new ExecutionEngine().executeCommand(command, ExecutionContext.of(1)); throw new AssertionError("Lost failure"); }
        catch (IllegalStateException expected) {
            assertTrue(expected == primary, "Primary failure was replaced");
            assertEquals(List.of(cleanup), List.of(expected.getSuppressed()));
        }
        TestDatabase db = new TestDatabase();
        var releases = new AtomicInteger();
        var storage = (sqlike.contract.IStorageManager) Proxy.newProxyInstance(
                Qp5AcceptanceTest.class.getClassLoader(), new Class<?>[]{sqlike.contract.IStorageManager.class,
                        sqlike.qp.port.StorageAdapter.TransactionScope.class}, (proxy, method, args) -> {
                    if (method.getName().equals("releaseReservation") && releases.incrementAndGet() == 1) {
                        throw new IllegalStateException("Release failed once");
                    }
                    try { return method.invoke(db.storage, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        var runner = new StubQueryRunner(storage, db.transactions, QpConfiguration.defaults());
        var result = runner.execute(new SessionState(), "INSERT INTO employees VALUES (1, 1000);");
        assertTrue(!result.success(), "Reservation cleanup failure reported success");
        assertEquals(2, releases.get());
        assertEquals(0, db.storage.reservationCount());
        assertEquals(List.of(), db.rows());

        var terminal = (IConcurrencyControl) Proxy.newProxyInstance(Qp5AcceptanceTest.class.getClassLoader(),
                new Class<?>[]{IConcurrencyControl.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getState")) return TransactionState.COMMITTED;
                    throw new AssertionError("Terminal transaction was finalized again: " + method.getName());
                });
        var attempts = new AtomicInteger();
        var cleanupStorage = (IStorageManager) Proxy.newProxyInstance(Qp5AcceptanceTest.class.getClassLoader(),
                new Class<?>[]{IStorageManager.class}, (proxy, method, args) -> {
                    assertEquals("releaseReservations", method.getName());
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("Cleanup retry needed");
                    return null;
                });
        var coordinator = new TransactionCoordinator(terminal, QpConfiguration.defaults(), cleanupStorage);
        var session = new SessionState(); session.attachTransaction(new TxId(1), true);
        try { coordinator.commit(session); throw new AssertionError("Cleanup failure was ignored"); }
        catch (IllegalStateException expected) { }
        assertTrue(session.currentTxId().isPresent(), "Cleanup failure discarded terminal transaction");
        assertEquals(TransactionState.COMMITTED, coordinator.abort(session, "Retry cleanup"));
        assertTrue(session.currentTxId().isEmpty(), "Cleanup retry did not clear terminal session");
        assertEquals(2, attempts.get());

    }

    private static void walPermitBoundaries() {
        for (Optional<Long> lsn : List.of(Optional.<Long>empty(), Optional.of(0L), Optional.of(-1L))) {
            var adapter = adapterWith((proxy, method, args) -> {
                assertEquals("requestWrite", method.getName());
                return new AccessResponse(AccessStatus.GRANTED, lsn, Optional.empty(), Optional.empty());
            });
            adapter.registerExternalTransaction(1);
            var tx = new TxId(1);
            var change = new DataChange(ChangeOperation.INSERT, "employees", new RID(new PageId("employees", 0), 0),
                    new RecordImage(false, List.of()), new RecordImage(true, List.of()));
            try {
                adapter.authorizeChange(new ChangeRecord(new ChangeId(tx, 1), tx, change));
                throw new AssertionError("Missing or invalid LSN became a write permit: " + lsn);
            } catch (QpException expected) {
                assertEquals(ErrorCode.WAL_FAILURE, expected.getErrorInfo().code());
            }
        }
    }

    private static void adapterAccessBoundary() {
        for (int scenario = 0; scenario < 3; scenario++) {
            final int selected = scenario;
            var reference = new java.util.concurrent.atomic.AtomicReference<ConcurrencyAdapter>();
            var adapter = adapterWith((proxy, method, args) -> {
                assertEquals("requestRead", method.getName());
                assertEquals(ResourceId.ofCatalog(), args[1]);
                if (selected == 1) reference.get().getCancellationToken(new TxId(1)).cancel();
                return new AccessResponse(selected == 2 ? AccessStatus.ABORTED : AccessStatus.GRANTED,
                        Optional.empty(), Optional.empty(), Optional.empty());
            });
            reference.set(adapter); adapter.registerExternalTransaction(1);
            try {
                var grant = adapter.acquireAccess(new TxId(1), ResourceKey.table(new TableId("__catalog__")), AccessMode.READ);
                assertEquals(selected == 2 ? AccessStatus.ABORTED : AccessStatus.GRANTED, grant.status());
                if (selected == 2) {
                    assertTrue(grant.grant().isEmpty(), "Aborted request returned a grant");
                    assertEquals(TransactionState.ABORTED, adapter.getState(new TxId(1)));
                }
            } catch (IllegalStateException expected) { assertEquals(1, selected); }
            catch (QpException expected) { assertEquals(2, selected); assertEquals(ErrorCode.TRANSACTION_ABORTED, expected.getErrorInfo().code()); }
        }
    }

    private static void failedDisconnectRollback() throws Exception {
        TestDatabase db = new TestDatabase();
        SessionManager sessions = new SessionManager();
        var processor = new QueryProcessor(db.runner.optimizer, db.runner.translator, db.runner.engine,
                sessions, db.runner.transactions, db.runner.access);
        processor.initialize(); var session = processor.openSession();
        assertEquals(ResultKind.TRANSACTION, processor.execute("BEGIN;", session).kind());
        db.transactions.failAbort = true;
        try { processor.closeSession(session.sessionId()); throw new AssertionError("Failed rollback closed session"); }
        catch (IllegalStateException expected) { }
        assertEquals(1, sessions.activeSessions().size());
        assertTrue(sessions.activeSessions().get(0).currentTxId().isPresent(), "Lost unresolved transaction");
        processor.closeSession(session.sessionId());
        assertTrue(sessions.activeSessions().isEmpty(), "Successful rollback left session registered");
        session = processor.openSession();
        processor.execute("BEGIN;", session);
        db.transactions.nextCommit = TransactionState.PARTIALLY_COMMITTED;
        var unknown = processor.execute("COMMIT;", session);
        assertEquals(ErrorCode.INVALID_STATE, unknown.error().orElseThrow().code());
        assertTrue(unknown.message().contains("Commit outcome unknown"), unknown.message());
        db.trace.clear();
        assertEquals(ResultKind.ERROR, processor.execute("ABORT;", session).kind());
        try { processor.closeSession(session.sessionId()); throw new AssertionError("Unknown commit outcome was discarded"); }
        catch (IllegalStateException expected) { }
        assertEquals(1, sessions.activeSessions().size());
        assertTrue(!db.trace.contains("FRM.rollback"), "Unknown commit was rolled back");
    }

    private static void strictGrants() {
        ExecutionContext context = ExecutionContext.of(1);
        try { context.requireGrant("student", AccessMode.READ); throw new AssertionError("Fabricated grant"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Missing"), expected.getMessage()); }
        context.addGrant(new AccessGrant(new TxId(1), ResourceKey.table(new TableId("student")), AccessMode.READ));
        try { context.requireGrant("student", AccessMode.WRITE); throw new AssertionError("READ upgraded itself"); }
        catch (IllegalStateException expected) { }
        try { context.addGrant(new AccessGrant(new TxId(2), ResourceKey.table(new TableId("student")), AccessMode.WRITE)); throw new AssertionError("Foreign grant"); }
        catch (IllegalStateException expected) { }
        Thread.currentThread().interrupt();
        try { context.check(); throw new AssertionError("Ignored interrupt"); }
        catch (IllegalStateException expected) { assertTrue(Thread.currentThread().isInterrupted(), "Interrupt lost"); }
        finally { Thread.interrupted(); }
    }

    private static void catalogBeforeOptimization() throws Exception {
        TestDatabase db = new TestDatabase();
        var bindings = new AtomicInteger();
        var optimizer = (sqlike.contract.IQueryOptimizer) Proxy.newProxyInstance(
                sqlike.contract.IQueryOptimizer.class.getClassLoader(), new Class<?>[]{sqlike.contract.IQueryOptimizer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("optimizeQuery")) {
                        bindings.incrementAndGet();
                        assertTrue(db.trace.contains("CCM.catalog.READ") || db.trace.contains("CCM.catalog.WRITE"), "Binding without catalog grant");
                    }
                    try { return method.invoke(db.runner.optimizer, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        var processor = new QueryProcessor(optimizer, db.runner.translator, db.runner.engine,
                new SessionManager(), db.runner.transactions, db.runner.access);
        processor.initialize();
        var session = processor.openSession();
        try {
            db.trace.clear();
            assertEquals(ResultKind.ROWS, processor.execute("SELECT * FROM employees;", session).kind());
            assertBefore(db.trace, "CCM.catalog.READ", "SM.scan");
            db.trace.clear();
            assertEquals(ResultKind.AFFECTED_ROWS, processor.execute("CREATE INDEX idx_stage4 ON employees (salary);", session).kind());
            assertTrue(db.trace.contains("CCM.catalog.WRITE") && !db.trace.contains("CCM.catalog.READ"), "DDL upgraded catalog S to X");
            int previousBindings = bindings.get();
            db.trace.clear(); db.transactions.scriptAccess(AccessStatus.ABORTED);
            var denied = processor.execute("SELECT * FROM employees;", session);
            assertEquals(ErrorCode.TRANSACTION_ABORTED, denied.error().orElseThrow().code());
            assertEquals(previousBindings, bindings.get());
            assertTrue(!db.trace.contains("SM.scan"), "Denied catalog access reached storage");
            assertEquals(ResultKind.ROWS, processor.execute("SELECT * FROM employees;", session).kind());
        } finally { processor.closeSession(session.sessionId()); }
    }

    private static void adapterWriteWait() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var adapter = adapterWith((proxy, method, args) -> {
            assertEquals("requestWrite", method.getName());
            calls.incrementAndGet(); entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Blocking request timed out");
            return new AccessResponse(AccessStatus.GRANTED, Optional.of(42L), Optional.empty(), Optional.empty());
        });
        var tx = new TxId(1); adapter.registerExternalTransaction(1);
        assertEquals(TransactionState.FAILED, adapter.getState(new TxId(99)));
        var rid = new RID(new PageId("employees", 0), 0);
        var change = new DataChange(ChangeOperation.INSERT, "employees", rid,
                new RecordImage(false, List.of()), new RecordImage(true, List.of()));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> adapter.authorizeChange(new ChangeRecord(new ChangeId(tx, 1), tx, change)));
            assertTrue(entered.await(3, TimeUnit.SECONDS), "CCM request not entered");
            assertTrue(!result.isDone(), "Write continued before CCM granted access");
            release.countDown(); assertEquals(new Lsn(42), result.get(5, TimeUnit.SECONDS).lsn());
            assertEquals(1, calls.get());
        } finally { release.countDown(); worker.shutdownNow(); }
        var retries = new AtomicInteger();
        var invalid = adapterWith((proxy, method, args) -> {
            retries.incrementAndGet();
            return new AccessResponse(AccessStatus.WAIT, Optional.empty(), Optional.empty(), Optional.empty());
        });
        invalid.registerExternalTransaction(1);
        try { invalid.authorizeChange(new ChangeRecord(new ChangeId(tx, 1), tx, change)); throw new AssertionError("Accepted returned WAIT"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("cannot retry"), expected.getMessage()); }
        assertEquals(1, retries.get());
    }

    private static void adapterAbortStatus() {
        var statuses = new ArrayDeque<>(List.of(TransactionStatus.FAILED, TransactionStatus.ABORTED));
        var adapter = adapterWith((proxy, method, args) -> {
            if (!method.getName().equals("abort")) {
                throw new AssertionError("Unexpected CCM call: " + method.getName());
            }
            return new TransactionResponse((long) args[0], 0, statuses.remove(), Optional.empty());
        });
        var coordinator = new TransactionCoordinator(adapter, QpConfiguration.defaults());
        var session = new SessionState();
        var tx = new TxId(1);
        adapter.registerExternalTransaction(tx.value());
        session.attachTransaction(tx, true);

        assertEquals(TransactionState.FAILED, coordinator.abort(session, "Test failed abort"));
        assertEquals(TransactionState.FAILED, adapter.getState(tx));
        assertEquals(Optional.of(tx), session.currentTxId());

        assertEquals(TransactionState.ABORTED, coordinator.abort(session, "Retry abort"));
        assertEquals(TransactionState.ABORTED, adapter.getState(tx));
        assertTrue(session.currentTxId().isEmpty(), "Completed abort did not clear the session");
    }

    private static ConcurrencyAdapter adapterWith(InvocationHandler handler) {
        var ccm = (IConcurrencyControlManager) Proxy.newProxyInstance(
                IConcurrencyControlManager.class.getClassLoader(),
                new Class<?>[] { IConcurrencyControlManager.class }, handler);
        return new ConcurrencyAdapter(ccm, null);
    }

    private static void accessOrderingAndMerging() throws Exception {
        TestDatabase database = new TestDatabase();
        AccessCoordinator coordinator = new AccessCoordinator(database.transactions);
        TxId tx = database.transactions.begin(database.session.sessionId());
        ExecutionContext context = ExecutionContext.of(tx.value());

        PreparedStatement stmt = new PreparedStatement(List.of(
                new AccessRequest(ResourceKey.table(new TableId("zeta")), AccessMode.READ),
                new AccessRequest(ResourceKey.table(new TableId("alpha")), AccessMode.READ),
                new AccessRequest(ResourceKey.table(new TableId("beta")), AccessMode.WRITE),
                new AccessRequest(ResourceKey.table(new TableId("alpha")), AccessMode.WRITE)
        ));

        database.trace.clear();
        coordinator.acquireAll(stmt, context);

        assertEquals(AccessMode.WRITE, context.requireGrant(new TableId("alpha"), AccessMode.WRITE).mode());
        assertEquals(AccessMode.WRITE, context.requireGrant(new TableId("beta"), AccessMode.WRITE).mode());
        assertEquals(AccessMode.READ, context.requireGrant(new TableId("zeta"), AccessMode.READ).mode());

        List<String> ccmAccessTrace = database.trace.stream()
                .filter(t -> t.startsWith("CCM.access."))
                .toList();
        assertEquals(List.of("CCM.access.WRITE", "CCM.access.WRITE", "CCM.access.READ"), ccmAccessTrace);
    }

    private static void textCodePointLength() {
        for (DataType type : List.of(DataType.CHAR, DataType.VARCHAR)) {
            Schema one = Schema.of(List.of(new ColumnSchema("code", type, Optional.of(1))));
            for (String text : List.of("", "a", "\u00e9", "\u4e2d", "\ud83d\ude80")) {
                Row result = one.validate(Row.of(new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of(text))));
                assertEquals(text, result.values().get(0).textValue().orElseThrow());
                assertEquals(type, result.values().get(0).type());
            }
            assertTextTooLong(one, "ab");
            assertTextTooLong(one, "\ud83d\ude80\ud83d\ude80");
            assertTextTooLong(one, "e\u0301");

            Schema three = Schema.of(List.of(new ColumnSchema("code", type, Optional.of(3))));
            for (String text : List.of("abc", "\u00e9\u4e2d\ud83d\ude80", "\ud83d\ude80\ud83d\ude80\ud83d\ude80")) {
                assertEquals(text, three.validate(Row.of(new Value(type, Optional.empty(), Optional.empty(), Optional.of(text)))).values().get(0).textValue().orElseThrow());
            }
            assertTextTooLong(three, "abcd");
            assertTextTooLong(three, "\u00e9\u4e2d\ud83d\ude80a");
        }
    }

    private static void assertTextTooLong(Schema schema, String text) {
        try {
            schema.validate(Row.of(new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of(text))));
            throw new AssertionError("Text exceeding the code-point limit was accepted");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Text too long"), e.getMessage());
        }
    }

    private static void unicodeTextDml() throws Exception {
        TestDatabase database = new TestDatabase();
        database.execute("CREATE TABLE unicode_text (id INTEGER PRIMARY KEY, code CHAR(1), label VARCHAR(3));");
        assertAffected(1, database.execute("INSERT INTO unicode_text VALUES (1, '\u00e9', '\u00e9\u4e2d\ud83d\ude80');"));
        assertEquals(List.of(List.of(1, "\u00e9", "\u00e9\u4e2d\ud83d\ude80")), values(database.execute("SELECT * FROM unicode_text;")));
        assertAffected(1, database.execute("UPDATE unicode_text SET code = '\ud83d\ude80' WHERE id = 1;"));
        database.trace.clear();
        Result rejectedInsert = database.runner.execute(database.session, "INSERT INTO unicode_text VALUES (2, 'x', '\u00e9\u4e2d\ud83d\ude80a');");
        assertTrue(!rejectedInsert.success() && rejectedInsert.message().contains("Text too long"), rejectedInsert.message());
        assertTrue(!database.trace.contains("SM.reserve"), "Oversized text reserved a RID");
        Result rejectedUpdate = database.runner.execute(database.session, "UPDATE unicode_text SET label = '\ud83d\ude80\ud83d\ude80\ud83d\ude80\ud83d\ude80' WHERE id = 1;");
        assertTrue(!rejectedUpdate.success() && rejectedUpdate.message().contains("Text too long"), rejectedUpdate.message());
        assertTrue(!database.trace.contains("SM.insert") && !database.trace.contains("SM.update"), "Oversized text reached storage writes");
        assertEquals(List.of(List.of(1, "\ud83d\ude80", "\u00e9\u4e2d\ud83d\ude80")), values(database.execute("SELECT * FROM unicode_text;")));
    }

    private static void metadataFailures(String operation) {
        List<String> points = switch (operation) {
            case "INSERT" -> List.of("target", "reference");
            case "UPDATE" -> List.of("target", "catalog", "parent");
            case "DELETE" -> List.of("catalog", "parent", "child");
            default -> throw new AssertionError(operation);
        };
        for (String point : points) {
            for (boolean missing : List.of(false, true)) {
                List<String> writes = new ArrayList<>();
                var error = new QpException(new ErrorInfo(ErrorCode.IO_ERROR, ComponentType.SM, "Metadata unavailable: " + point, Optional.empty()));
                var columns = List.of(new ColumnSchema("id", DataType.INTEGER, Optional.empty()));
                var parent = new TableSchema("parent", "parent", columns);
                var child = new TableSchema("child", "child", columns, List.of(), List.of(new ForeignKeySpec(List.of("id"), "parent", List.of("id"))));
                TableId target = new TableId(point.equals("reference") ? "child" : "parent");
                AtomicInteger parentReads = new AtomicInteger();
                IStorageManager storage = (IStorageManager) Proxy.newProxyInstance(Qp5AcceptanceTest.class.getClassLoader(), new Class<?>[]{IStorageManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getTableSchema")) {
                        String table = ((TableId) args[0]).value();
                        int reads = table.equals("parent") ? parentReads.incrementAndGet() : 0;
                        boolean fail = switch (point) {
                            case "target" -> table.equals(target.value());
                            case "parent" -> table.equals("parent") && reads == (operation.equals("UPDATE") ? 2 : 1);
                            case "child" -> table.equals("child");
                            case "reference" -> table.equals("parent");
                            default -> false;
                        };
                        if (fail) {if (missing) return null; throw error;}
                        return table.equals("parent") ? parent : child;
                    }
                    return switch (method.getName()) {
                        case "getSchema" -> Schema.fromTableSchema(target.value().equals("parent") ? parent : child);
                        case "listTables" -> {
                            if (point.equals("catalog")) {if (missing) yield null; throw error;}
                            yield List.of("parent", "child");
                        }
                        case "scan" -> new sqlike.qp.port.QpContracts.RecordCursor() {
                            @Override public Optional<StoredRecord> next() {return Optional.empty();}
                            @Override public void close() { }
                        };
                        case "reserveInsert" -> {writes.add("reserve"); yield new Rid(new RID(new PageId(target.value(), 0), 0));}
                        case "applyChange", "releaseReservation" -> {writes.add(method.getName()); yield null;}
                        default -> throw new AssertionError("Unexpected storage call: " + method.getName());
                    };
                });
                IConcurrencyControl concurrency = (IConcurrencyControl) Proxy.newProxyInstance(Qp5AcceptanceTest.class.getClassLoader(), new Class<?>[]{IConcurrencyControl.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getState" -> TransactionState.ACTIVE;
                    case "authorizeChange" -> {writes.add("authorize"); yield new WritePermit(((ChangeRecord) args[0]).id(), new Lsn(1));}
                    case "acquireApplyLease" -> {writes.add("lease"); yield (WriteLease) () -> {};}
                    case "confirmApplied" -> {writes.add("confirm"); yield null;}
                    default -> throw new AssertionError("Unexpected CCM call: " + method.getName());
                });
                var coordinator = new MutationCoordinator(storage, concurrency);
                ExecutionContext context = ExecutionContext.of(51);
                for (String table : List.of("parent", "child")) {
                    context.addGrant(new AccessGrant(new TxId(51), ResourceKey.table(new TableId(table)), AccessMode.WRITE));
                }
                Row values = Row.of(sqlike.qo.Literals.integer(2));
                StoredRecord before = new StoredRecord(new RID(new PageId(target.value(), 0), 0), Row.of(sqlike.qo.Literals.integer(1)));
                boolean rejected = false;
                try {
                    switch (operation) {
                        case "INSERT" -> coordinator.insert(target, values, context);
                        case "UPDATE" -> coordinator.update(target, before, values, context);
                        case "DELETE" -> coordinator.delete(target, before, context);
                        default -> throw new AssertionError(operation);
                    }
                } catch (QpException actual) {
                    rejected = true;
                    assertEquals(ErrorCode.IO_ERROR, actual.getErrorInfo().code());
                    assertEquals(ComponentType.SM, actual.getErrorInfo().component());
                    if (!missing) assertTrue(actual == error, "Original metadata error was replaced");
                }
                assertTrue(rejected, operation + " ignored " + point + " metadata failure; writes=" + writes);
                assertTrue(writes.isEmpty(), "Metadata failure reached reservation, WAL, or apply: " + writes);
            }
        }
    }

    private static void createIndexes() throws Exception {
        TestDatabase database = new TestDatabase();

        database.execute("BEGIN;");
        Result rejectedInTx = database.runner.execute(database.session, "CREATE INDEX by_id_fail ON employees(id);");
        assertTrue(!rejectedInTx.success(), "CREATE INDEX should fail inside explicit transaction");
        assertTrue(rejectedInTx.message().contains("not allowed within an explicit transaction"),
                "Wrong error message: " + rejectedInTx.message());
        assertTrue(!database.trace.contains("SM.createIndex.by_id_fail"), "SM index created despite rejection");
        assertTrue(database.session.getCurrentTxId().isPresent(), "Transaction was aborted or cleared");
        database.execute("ABORT;");

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

        Result joined = database.execute(
                "SELECT employees.id, departments.dept_name, employees.salary "
                + "FROM employees JOIN departments ON employees.id = departments.id "
                + "ORDER BY employees.salary ASC;");
        assertEquals(
                List.of(List.of(2, "Finance", 1000.0), List.of(1, "Engineering", 3000.0)),
                values(joined));

        Result natural = database.execute(
                "SELECT id, dept_name, salary FROM employees NATURAL JOIN departments ORDER BY id DESC;");
        assertEquals(
                List.of(List.of(2, "Finance", 1000.0), List.of(1, "Engineering", 3000.0)),
                values(natural));

        database.execute("BEGIN;");
        Result deleted = database.execute("DELETE FROM employees WHERE id = 2;");
        assertAffected(1, deleted);
        assertEquals(List.of(List.of(1, 3000.0), List.of(3, 2000.0)), database.rows());
        database.execute("ABORT;");
        assertEquals(List.of(List.of(1, 3000.0), List.of(2, 1000.0), List.of(3, 2000.0)), database.rows());
    }

    private static void createTableAndConstraints() throws Exception {
        TestDatabase database = new TestDatabase();

        database.execute("BEGIN;");
        Result inTx = database.runner.execute(database.session,
                "CREATE TABLE depts_fail (id INTEGER PRIMARY KEY, title VARCHAR(16));");
        assertTrue(!inTx.success(), "CREATE TABLE must fail inside explicit transaction");
        assertTrue(inTx.message().contains("not allowed within an explicit transaction"), inTx.message());
        assertTrue(database.session.getCurrentTxId().isPresent(), "Active transaction must remain open");
        database.execute("ABORT;");

        Result createdParent = database.execute(
                "CREATE TABLE departments (id INTEGER, title VARCHAR(20), PRIMARY KEY (id));");
        assertEquals("Table departments created successfully", createdParent.message());

        Result createdChild = database.execute(
                "CREATE TABLE staff (id INTEGER, dept_id INTEGER, code CHAR(3), "
                + "PRIMARY KEY (id), FOREIGN KEY (dept_id) REFERENCES departments(id));");
        assertEquals("Table staff created successfully", createdChild.message());

        Result dupTable = database.runner.execute(database.session,
                "CREATE TABLE departments (id INTEGER PRIMARY KEY);");
        assertTrue(!dupTable.success(), "Duplicate CREATE TABLE must be rejected");

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

        assertAffected(1, database.execute("DELETE FROM departments WHERE id = 20;"));
    }

    private static void dropTableRestrictAndCascade() throws Exception {
        TestDatabase database = new TestDatabase();
        database.execute("CREATE TABLE departments (id INTEGER PRIMARY KEY, title VARCHAR(20));");
        database.execute("CREATE TABLE staff (id INTEGER PRIMARY KEY, dept_id INTEGER, "
                + "FOREIGN KEY (dept_id) REFERENCES departments(id));");
        database.execute("INSERT INTO departments VALUES (1, 'Core');");
        database.execute("INSERT INTO staff VALUES (50, 1);");

        database.execute("BEGIN;");
        Result inTx = database.runner.execute(database.session, "DROP TABLE staff;");
        assertTrue(!inTx.success(), "DROP TABLE must fail inside explicit transaction");
        assertTrue(database.session.getCurrentTxId().isPresent(), "Active transaction must remain open");
        database.execute("ABORT;");

        Result restricted = database.runner.execute(database.session, "DROP TABLE departments RESTRICT;");
        assertTrue(!restricted.success(), "DROP TABLE RESTRICT must fail when referenced by another table's FK");
        assertTrue(database.storage.listTables().contains("departments"), "departments must remain after RESTRICT failure");

        // CASCADE removes the foreign key and preserves the child rows.
        Result cascaded = database.execute("DROP TABLE departments CASCADE;");
        assertEquals("Table departments dropped successfully", cascaded.message());
        var schemaChange = database.transactions.lastSchemaChange.value();
        assertEquals(2, schemaChange.before().size()); assertEquals(2, schemaChange.after().size());
        var droppedImage = schemaChange.before().stream().filter(image -> image.tableName().equals("departments")).findFirst().orElseThrow();
        assertTrue(database.storage.hasSnapshot(droppedImage.snapshotId().orElseThrow()), "CASCADE logged a placeholder snapshot");
        assertTrue(schemaChange.after().stream().filter(image -> image.tableName().equals("staff"))
                .findFirst().orElseThrow().schema().orElseThrow().foreignKeys().isEmpty(), "CASCADE omitted child schema change");
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

        Result top2 = database.execute("SELECT id, salary FROM employees ORDER BY salary DESC LIMIT 2;");
        assertEquals(List.of(List.of(2, 3000.0), List.of(3, 2000.0)), values(top2));

        Result limit0 = database.execute("SELECT id, salary FROM employees LIMIT 0;");
        assertEquals(List.of(), values(limit0));
        assertEquals(2, limit0.schema().columns().size());

        Result aliased = database.execute("SELECT e.id, e.salary FROM employees AS e WHERE e.id = 2;");
        assertEquals(List.of(List.of(2, 3000.0)), values(aliased));

        Result invalidOrigQualifier = database.runner.execute(
                database.session, "SELECT employees.id FROM employees AS e;");
        assertTrue(!invalidOrigQualifier.success(), "Original table name qualifier must fail when table is aliased");

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

        Result scalarOne = database.execute(
                "SELECT id FROM employees WHERE salary > (SELECT salary FROM employees WHERE id = 2);");
        assertEquals(List.of(List.of(3)), values(scalarOne));

        // An empty scalar subquery yields SQL NULL, making the predicate UNKNOWN.
        Result scalarZero = database.execute(
                "SELECT id FROM employees WHERE salary = (SELECT salary FROM employees WHERE id = 999);");
        assertEquals(List.of(), values(scalarZero));

        Result scalarMulti = database.runner.execute(
                database.session,
                "SELECT id FROM employees WHERE salary = (SELECT salary FROM employees);");
        assertTrue(!scalarMulti.success(), "SCALAR subquery returning >1 row must fail");
        assertTrue(scalarMulti.message().contains("more than one row"), scalarMulti.message());

        Result inSub = database.execute(
                "SELECT id FROM employees WHERE id IN (SELECT lead_id FROM projects) ORDER BY id ASC;");
        assertEquals(List.of(List.of(2), List.of(3)), values(inSub));

        Result existsCorrelated = database.execute(
                "SELECT e.id FROM employees AS e "
                + "WHERE EXISTS (SELECT p.id FROM projects AS p WHERE p.lead_id = e.id AND p.budget > 60000);");
        assertEquals(List.of(List.of(3)), values(existsCorrelated));

        // Scalar subquery inside UPDATE SET expression (propagates ExecutionContext)
        Result updateScalar = database.execute(
                "UPDATE employees SET salary = (SELECT budget FROM projects WHERE id = 10) WHERE id = 1;");
        assertAffected(1, updateScalar);
        assertEquals(List.of(List.of(50000.0)), values(database.execute("SELECT salary FROM employees WHERE id = 1;")));

        // 0-row scalar subquery inside UPDATE SET must fail because columns are non-null
        Result updateNullScalar = database.runner.execute(
                database.session,
                "UPDATE employees SET salary = (SELECT budget FROM projects WHERE id = 999) WHERE id = 1;");
        assertTrue(!updateNullScalar.success(), "UPDATE with 0-row scalar subquery (NULL) must fail");
    }

    private static void tcpServerMultiClientAndDisconnectAbort() throws Exception {
        TestDatabase database = new TestDatabase();
        SessionManager sessionManager = new SessionManager();
        QueryProcessor qp = new QueryProcessor(
                database.runner.optimizer,
                database.runner.translator,
                database.runner.engine,
                sessionManager,
                database.runner.transactions,
                database.runner.access);
        QpConfiguration config = new QpConfiguration(0, 4, 4096, IndexType.HASH, Path.of("."));
        QueryServer server = new QueryServer(qp, config);
        QpBootstrap bootstrap = new QpBootstrap(database.transactions, database.transactions, server);
        bootstrap.start();
        try {
            assertEquals(SystemState.READY, bootstrap.getState());
            int port = server.getBoundPort();
            assertTrue(port > 0, "Server bound to ephemeral TCP port");

            java.io.StringWriter c1Out = new java.io.StringWriter();
            try (CliClient client1 = new CliClient(
                    new ProtocolCodec(),
                    new StatementReader(),
                    new ConsoleResultSink(new java.io.PrintWriter(c1Out, true)))) {
                client1.connect("127.0.0.1", port);
                client1.run(new java.io.BufferedReader(new java.io.StringReader(
                        "BEGIN;\nINSERT INTO employees VALUES (77, 7700);\n")));
            }

            // Wait for the server to process EOF and roll back the open transaction.
            long deadline = System.currentTimeMillis() + 3000;
            while (!sessionManager.activeSessions().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(0, sessionManager.activeSessions().size());
            assertEquals(List.of(), database.rows());

            java.io.StringWriter c2Out = new java.io.StringWriter();
            try (CliClient client2 = new CliClient(
                    new ProtocolCodec(),
                    new StatementReader(),
                    new ConsoleResultSink(new java.io.PrintWriter(c2Out, true)))) {
                client2.connect("127.0.0.1", port);
                client2.run(new java.io.BufferedReader(new java.io.StringReader(
                        "INSERT INTO employees VALUES (88, 8800);\nSELECT id, salary FROM employees;\n\\q\n")));
            }
            String rendered = c2Out.toString();
            assertTrue(rendered.contains("1 row(s) affected"), rendered);
            assertTrue(rendered.contains("| 88 | 8800.0 |"), rendered);
            assertTrue(rendered.contains("1 row(s) returned"), rendered);
            assertEquals(List.of(List.of(88, 8800.0)), database.rows());
        } finally {
            bootstrap.stop();
            assertEquals(SystemState.STOPPED, bootstrap.getState());
        }
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
