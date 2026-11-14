package sqlike.qp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import sqlike.contract.IStorageManager;
import sqlike.qp.port.QpContracts.AccessGrant;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.CancellationToken;
import sqlike.qp.port.QpContracts.ResourceKey;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.qp.port.QpContracts.TxId;
import sqlike.qp.port.StorageAdapter;
import sqlike.shared.ColumnRef;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.ExpressionOperator;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexOperator;
import sqlike.shared.InsertReservation;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.RecordImage;
import sqlike.shared.ScanSpec;
import sqlike.shared.TableSchema;
import sqlike.shared.TableStatistics;
import sqlike.shared.Value;
import sqlike.storage.StorageErrorCode;
import sqlike.storage.StorageException;

public class QP3TestDriver {

    private static int testsRun = 0;
    private static int testsPassed = 0;

    private static ExecutionContext grantedContext(long txId) {
        ExecutionContext context = ExecutionContext.of(txId);
        context.addGrant(new AccessGrant(new TxId(txId), ResourceKey.table(new TableId("students")), AccessMode.WRITE));
        return context;
    }

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   SQLike QP3 (Row Operators & Delete) Tests      ");
        System.out.println("   Milestone 2 & PRD v1.0 Verification            ");
        System.out.println("==================================================");

        try {
            testTableScanOperator();
            testIndexScanOperator();
            testFilterOperator();
            testProjectOperator();
            testLimitOperator();
            testMutationTargetCollector();
            testDeleteExecutor();
            System.out.println("\n--------------------------------------------------");
            System.out.println("RESULT: All " + testsPassed + " / " + testsRun + " tests PASSED successfully!");
            System.out.println("--------------------------------------------------");
        } catch (Throwable t) {
            System.err.println("\nTEST SUITE FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        testsRun++;
        if (expected == null && actual == null) {
            testsPassed++;
            return;
        }
        if (expected != null && expected.equals(actual)) {
            testsPassed++;
            return;
        }
        throw new AssertionError(message + " - Expected: " + expected + ", Actual: " + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        testsRun++;
        if (condition) {
            testsPassed++;
        } else {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static Value intVal(int v) {
        return new Value(DataType.INTEGER, Optional.of(v), Optional.empty(), Optional.empty());
    }

    private static Value floatVal(double v) {
        return new Value(DataType.FLOAT, Optional.empty(), Optional.of(v), Optional.empty());
    }

    private static Value strVal(String v) {
        return new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of(v));
    }

    private static Schema studentSchema() {
        return Schema.of("students", List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("name", DataType.VARCHAR, Optional.of(64)),
                new ColumnSchema("age", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("gpa", DataType.FLOAT, Optional.empty())));
    }

    private static InMemoryMockStorage seedStudentStorage() {
        InMemoryMockStorage storage = new InMemoryMockStorage();
        RID r1 = new RID(new PageId("students", 1L), 0);
        RID r2 = new RID(new PageId("students", 1L), 1);
        RID r3 = new RID(new PageId("students", 1L), 2);

        storage.putRecord("students", new Record(r1, List.of(intVal(1), strVal("Alice"), intVal(22), floatVal(3.8))));
        storage.putRecord("students", new Record(r2, List.of(intVal(2), strVal("Bob"), intVal(19), floatVal(3.2))));
        storage.putRecord("students", new Record(r3, List.of(intVal(3), strVal("Charlie"), intVal(23), floatVal(3.9))));
        storage.putIndexResults("idx_students_age", List.of(r1, r3));
        return storage;
    }

    // 1. TableScanOperator Tests
    private static void testTableScanOperator() {
        System.out.println("[TEST] TableScanOperator (scan, rescan, empty, PRD error & cursor rules)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema schema = studentSchema();
        ScanSpec spec = new ScanSpec("students", Optional.empty(), Optional.empty(), Optional.of("s"));
        TableScanOperator scanOp = new TableScanOperator(storage, spec, schema);
        assertEquals("s", scanOp.getRelationId(), "TableScanOperator must expose alias as relationId when present");
        ExecutionContext ctx = grantedContext(101L);

        boolean threwBeforeOpen = false;
        try {
            scanOp.next();
        } catch (QpException e) {
            threwBeforeOpen = (e.getErrorInfo().code() == ErrorCode.INVALID_STATE);
        }
        assertTrue(threwBeforeOpen, "next() before open() must throw QpException(INVALID_STATE)");

        scanOp.open(ctx);
        List<RowEnvelope> firstPass = new ArrayList<>();
        while (true) {
            Optional<RowEnvelope> row = scanOp.next();
            if (row.isEmpty()) {
                break;
            }
            firstPass.add(row.get());
        }
        assertEquals(3, firstPass.size(), "TableScanOperator must yield 3 student rows");
        assertTrue(firstPass.get(0).rid().isPresent(), "Scanned RowEnvelope must carry RID");
        assertEquals(strVal("Alice"), firstPass.get(0).row().getValue(1), "First student should be Alice");
        assertEquals(OperatorState.EXHAUSTED, scanOp.getState(), "State after exhaustion should be EXHAUSTED");
        scanOp.close();
        assertEquals(OperatorState.CLOSED, scanOp.getState(), "State after close should be CLOSED");

        scanOp.open(ctx);
        Optional<RowEnvelope> firstAgain = scanOp.next();
        assertTrue(firstAgain.isPresent(), "Rescan must yield first row again");
        assertEquals(intVal(1), firstAgain.get().row().getValue(0), "First row id on rescan must be 1");
        scanOp.close();

        TableScanOperator emptyScan = new TableScanOperator(
                storage,
                new ScanSpec("empty_table", Optional.empty(), Optional.empty()),
                schema);
        emptyScan.open(ctx);
        assertTrue(emptyScan.next().isEmpty(), "Empty table scan must return Optional.empty()");
        emptyScan.close();

        // PRD §8: StorageException(TABLE_NOT_FOUND) maps to ErrorCode.NOT_FOUND
        storage.setMissingTableError("missing_tbl");
        TableScanOperator missingTblScan = new TableScanOperator(
                storage,
                new ScanSpec("missing_tbl", Optional.empty(), Optional.empty()),
                schema);
        boolean threwTableNotFound = false;
        try {
            missingTblScan.open(ctx);
        } catch (QpException e) {
            threwTableNotFound = (e.getErrorInfo().code() == ErrorCode.NOT_FOUND);
        }
        assertTrue(threwTableNotFound, "StorageException(TABLE_NOT_FOUND) must map to ErrorCode.NOT_FOUND");

        // PRD §2 & §8: UncheckedIOException during cursor read maps to ErrorCode.IO_ERROR and closes cursor
        storage.setFailCursorOnTable("io_fail_tbl");
        TableScanOperator ioFailScan = new TableScanOperator(
                storage,
                new ScanSpec("io_fail_tbl", Optional.empty(), Optional.empty()),
                schema);
        ioFailScan.open(ctx);
        boolean threwIoError = false;
        try {
            ioFailScan.next();
        } catch (QpException e) {
            threwIoError = (e.getErrorInfo().code() == ErrorCode.IO_ERROR);
        }
        assertTrue(threwIoError, "UncheckedIOException on cursor must map to ErrorCode.IO_ERROR");
        assertTrue(storage.wasLastFailingCursorClosed(), "Failing cursor must be closed automatically on failure");
        ioFailScan.close();
    }

    // 2. IndexScanOperator Tests
    private static void testIndexScanOperator() {
        System.out.println("[TEST] IndexScanOperator (lookupIndex, fetchRecord, missing/invalid RID)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema schema = studentSchema();
        IndexCondition cond = new IndexCondition(
                IndexOperator.GE, intVal(21), Optional.empty(), true, false);
        IndexScanSpec spec = new IndexScanSpec("students", "idx_students_age", Optional.of(cond));
        IndexScanOperator indexOp = new IndexScanOperator(storage, spec, schema);
        ExecutionContext ctx = grantedContext(102L);

        indexOp.open(ctx);
        Optional<RowEnvelope> row1 = indexOp.next();
        Optional<RowEnvelope> row2 = indexOp.next();
        Optional<RowEnvelope> row3 = indexOp.next();

        assertTrue(row1.isPresent(), "IndexScan first matching row should be present");
        assertTrue(row2.isPresent(), "IndexScan second matching row should be present");
        assertTrue(row3.isEmpty(), "IndexScan should be exhausted after 2 matching RIDs");
        assertEquals(strVal("Alice"), row1.get().row().getValue(1), "First indexed student should be Alice");
        assertEquals(strVal("Charlie"), row2.get().row().getValue(1), "Second indexed student should be Charlie");
        assertTrue(row1.get().rid().isPresent(), "IndexScan must preserve RID");
        indexOp.close();

        RID danglingRid = new RID(new PageId("students", 99L), 99);
        storage.putIndexResults("idx_dangling", List.of(danglingRid));
        IndexScanOperator brokenIndexOp = new IndexScanOperator(
                storage,
                new IndexScanSpec("students", "idx_dangling", Optional.empty()),
                schema);
        brokenIndexOp.open(ctx);
        boolean threwNotFound = false;
        try {
            brokenIndexOp.next();
        } catch (QpException e) {
            threwNotFound = (e.getErrorInfo().code() == ErrorCode.NOT_FOUND);
        } finally {
            brokenIndexOp.close();
        }
        assertTrue(threwNotFound, "IndexScan with dangling RID must throw QpException(NOT_FOUND)");

        // PRD §8: Negative RID or wrong-table RID must throw ErrorCode.INVALID_VALUE
        RID negativeRid = new RID(new PageId("students", -1L), 0);
        storage.putIndexResults("idx_neg_rid", List.of(negativeRid));
        IndexScanOperator negRidOp = new IndexScanOperator(
                storage,
                new IndexScanSpec("students", "idx_neg_rid", Optional.empty()),
                schema);
        negRidOp.open(ctx);
        boolean threwInvalidRid = false;
        try {
            negRidOp.next();
        } catch (QpException e) {
            threwInvalidRid = (e.getErrorInfo().code() == ErrorCode.INVALID_VALUE);
        } finally {
            negRidOp.close();
        }
        assertTrue(threwInvalidRid, "Negative RID in IndexScan must throw QpException(INVALID_VALUE)");

        // PRD §8: StorageException(INDEX_NOT_FOUND) maps to ErrorCode.NOT_FOUND
        storage.setMissingIndexError("idx_nonexistent");
        IndexScanOperator missingIdxOp = new IndexScanOperator(
                storage,
                new IndexScanSpec("students", "idx_nonexistent", Optional.empty()),
                schema);
        boolean threwIdxNotFound = false;
        try {
            missingIdxOp.open(ctx);
        } catch (QpException e) {
            threwIdxNotFound = (e.getErrorInfo().code() == ErrorCode.NOT_FOUND);
        }
        assertTrue(threwIdxNotFound, "StorageException(INDEX_NOT_FOUND) must map to ErrorCode.NOT_FOUND");
    }

    // 3. FilterOperator Tests
    private static void testFilterOperator() {
        System.out.println("[TEST] FilterOperator (predicate filtering, no-match, cancellation)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema schema = studentSchema();
        TableScanOperator scanOp = new TableScanOperator(
                storage, new ScanSpec("students", Optional.empty(), Optional.empty()), schema);

        Expression colAge = new Expression(
                ExpressionKind.COLUMN,
                Optional.empty(),
                Optional.of(new ColumnRef(Optional.of("students"), "age")),
                Optional.empty(),
                List.of());
        Expression lit21 = new Expression(
                ExpressionKind.LITERAL,
                Optional.of(intVal(21)),
                Optional.empty(),
                Optional.empty(),
                List.of());
        Expression predicate = new Expression(
                ExpressionKind.BINARY,
                Optional.empty(),
                Optional.empty(),
                Optional.of(ExpressionOperator.GE),
                List.of(colAge, lit21));

        IExpressionEvaluator evaluator = new ExpressionEvaluator(new ValueComparator());
        FilterOperator filterOp = new FilterOperator(scanOp, new FilterSpec(predicate), evaluator);
        ExecutionContext ctx = grantedContext(103L);

        filterOp.open(ctx);
        List<RowEnvelope> filtered = new ArrayList<>();
        while (true) {
            Optional<RowEnvelope> next = filterOp.next();
            if (next.isEmpty()) {
                break;
            }
            filtered.add(next.get());
        }
        filterOp.close();

        assertEquals(2, filtered.size(), "Filter age >= 21 must keep 2 rows (Alice & Charlie)");
        assertEquals(strVal("Alice"), filtered.get(0).row().getValue(1), "First filtered row should be Alice");
        assertEquals(strVal("Charlie"), filtered.get(1).row().getValue(1), "Second filtered row should be Charlie");
        assertTrue(filtered.get(0).rid().isPresent(), "FilterOperator must preserve RID on RowEnvelope");

        // No-match filter returns empty result without error (QP-06)
        Expression lit99 = new Expression(
                ExpressionKind.LITERAL,
                Optional.of(intVal(99)),
                Optional.empty(),
                Optional.empty(),
                List.of());
        Expression impossiblePred = new Expression(
                ExpressionKind.BINARY,
                Optional.empty(),
                Optional.empty(),
                Optional.of(ExpressionOperator.GT),
                List.of(colAge, lit99));
        FilterOperator noMatchOp = new FilterOperator(scanOp, new FilterSpec(impossiblePred), evaluator);
        noMatchOp.open(ctx);
        assertTrue(noMatchOp.next().isEmpty(), "No-match WHERE predicate must return empty without error");
        noMatchOp.close();

        // Cancellation token during filter iteration throws TRANSACTION_ABORTED
        CancellationToken cancelToken = new CancellationToken();
        ExecutionContext cancelCtx = new ExecutionContext(1031L, Optional.empty(), true, new AtomicLong(), cancelToken);
        filterOp.open(cancelCtx);
        cancelToken.cancel();
        boolean threwAborted = false;
        try {
            filterOp.next();
        } catch (QpException e) {
            threwAborted = (e.getErrorInfo().code() == ErrorCode.TRANSACTION_ABORTED);
        } finally {
            filterOp.close();
        }
        assertTrue(threwAborted, "Cancelled ExecutionContext in FilterOperator must throw TRANSACTION_ABORTED");
    }

    // 4. ProjectOperator Tests
    private static void testProjectOperator() {
        System.out.println("[TEST] ProjectOperator (column projection, wildcard *, prefix t.*, RID)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema baseSchema = studentSchema();
        TableScanOperator scanOp = new TableScanOperator(
                storage, new ScanSpec("students", Optional.empty(), Optional.empty()), baseSchema);

        ProjectSpec projectSpec = new ProjectSpec(List.of(
                new ColumnRef(Optional.of("students"), "name"),
                new ColumnRef(Optional.of("students"), "id")));
        Schema projectedSchema = Schema.of("students", List.of(
                new ColumnSchema("name", DataType.VARCHAR, Optional.of(64)),
                new ColumnSchema("id", DataType.INTEGER, Optional.empty())));

        ProjectOperator projectOp = new ProjectOperator(scanOp, projectSpec, projectedSchema);
        ExecutionContext ctx = grantedContext(104L);

        projectOp.open(ctx);
        Optional<RowEnvelope> first = projectOp.next();
        assertTrue(first.isPresent(), "Projected first row should be present");
        assertEquals(2, first.get().row().size(), "Projected row must have 2 columns");
        assertEquals(strVal("Alice"), first.get().row().getValue(0), "Column 0 must be projected 'name'");
        assertEquals(intVal(1), first.get().row().getValue(1), "Column 1 must be projected 'id'");
        assertTrue(first.get().rid().isPresent(), "ProjectOperator must preserve source RID");
        projectOp.close();

        // Direct wildcard '*' expansion in ProjectOperator
        ProjectSpec wildcardSpec = new ProjectSpec(List.of(new ColumnRef(Optional.empty(), "*")));
        ProjectOperator wildcardOp = new ProjectOperator(scanOp, wildcardSpec);
        assertEquals(4, wildcardOp.schema().columnCount(), "Wildcard '*' must expand to all 4 columns");
        wildcardOp.open(ctx);
        Optional<RowEnvelope> wildFirst = wildcardOp.next();
        assertTrue(wildFirst.isPresent() && wildFirst.get().row().size() == 4, "Wildcard '*' row has 4 values");
        wildcardOp.close();

        // Prefixed wildcard 'students.*' expansion in ProjectOperator
        ProjectSpec prefixedWildcardSpec = new ProjectSpec(List.of(new ColumnRef(Optional.of("students"), "*")));
        ProjectOperator prefixedOp = new ProjectOperator(scanOp, prefixedWildcardSpec);
        assertEquals(4, prefixedOp.schema().columnCount(), "Prefixed wildcard 'students.*' must expand to 4 columns");
        prefixedOp.open(ctx);
        assertTrue(prefixedOp.next().isPresent(), "Prefixed wildcard projection yields row");
        prefixedOp.close();

        // Unknown table prefix 'unknown.*' must fail with IllegalArgumentException
        boolean threwUnknownPrefix = false;
        try {
            new ProjectOperator(scanOp, new ProjectSpec(List.of(new ColumnRef(Optional.of("unknown"), "*"))));
        } catch (IllegalArgumentException e) {
            threwUnknownPrefix = true;
        }
        assertTrue(threwUnknownPrefix, "Unknown prefix in 'unknown.*' must throw IllegalArgumentException");
    }

    // 4b. LimitOperator Tests (QP-16* Bonus)
    private static void testLimitOperator() {
        System.out.println("[TEST] LimitOperator (LIMIT k, LIMIT 0 header preservation, early close)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema baseSchema = studentSchema();
        TableScanOperator scanOp = new TableScanOperator(
                storage, new ScanSpec("students", Optional.empty(), Optional.empty()), baseSchema);

        LimitOperator limit2 = new LimitOperator(scanOp, new sqlike.shared.LimitSpec(2));
        ExecutionContext ctx = grantedContext(1041L);
        limit2.open(ctx);
        Optional<RowEnvelope> r1 = limit2.next();
        Optional<RowEnvelope> r2 = limit2.next();
        Optional<RowEnvelope> r3 = limit2.next();
        assertTrue(r1.isPresent() && r2.isPresent() && r3.isEmpty(), "LIMIT 2 yields exactly 2 rows");
        assertEquals(OperatorState.CLOSED, scanOp.getState(), "LimitOperator closes child immediately once limit is reached");
        limit2.close();

        LimitOperator limit0 = new LimitOperator(scanOp, new sqlike.shared.LimitSpec(0));
        limit0.open(ctx);
        assertEquals(4, limit0.schema().columnCount(), "LIMIT 0 preserves output schema columns");
        assertTrue(limit0.next().isEmpty(), "LIMIT 0 yields 0 rows");
        assertEquals(OperatorState.CLOSED, scanOp.getState(), "LIMIT 0 closes child immediately");
        limit0.close();
    }

    // 5. MutationTargetCollector Tests
    private static void testMutationTargetCollector() {
        System.out.println("[TEST] MutationTargetCollector (materialization, deduplication, RID validation)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema schema = studentSchema();
        TableScanOperator scanOp = new TableScanOperator(
                storage, new ScanSpec("students", Optional.empty(), Optional.empty()), schema);

        MutationTargetCollector collector = new MutationTargetCollector(storage);
        ExecutionContext ctx = grantedContext(105L);

        List<StoredRecord> collected = collector.collect(scanOp, "students", ctx);
        assertEquals(3, collected.size(), "Collector must materialize all 3 records from scan");
        assertEquals(OperatorState.CLOSED, scanOp.getState(), "Collector must close source operator in finally block");

        // Duplicate RID deduplication check
        RID dupRid = new RID(new PageId("students", 1L), 0);
        Operator duplicateSource = new AbstractOperator(schema) {
            private int idx = 0;

            @Override
            protected void doOpen() {
                idx = 0;
            }

            @Override
            protected Optional<RowEnvelope> doNext() {
                if (idx >= 2) {
                    return Optional.empty();
                }
                idx++;
                return Optional.of(RowEnvelope.of(
                        Row.of(List.of(intVal(1), strVal("Alice"), intVal(22), floatVal(3.8))),
                        dupRid));
            }

            @Override
            protected void doClose() {
            }
        };
        List<StoredRecord> deduplicated = collector.collect(duplicateSource, "students", ctx);
        assertEquals(1, deduplicated.size(), "MutationTargetCollector must deduplicate identical RIDs");

        // Missing RID must trigger INVALID_STATE
        Operator ridlessSource = new AbstractOperator(schema) {
            private boolean emitted = false;

            @Override
            protected void doOpen() {
                emitted = false;
            }

            @Override
            protected Optional<RowEnvelope> doNext() {
                if (emitted) {
                    return Optional.empty();
                }
                emitted = true;
                return Optional.of(RowEnvelope.of(Row.of(List.of(intVal(1), strVal("NoRid"), intVal(20), floatVal(3.0)))));
            }

            @Override
            protected void doClose() {
            }
        };

        boolean threwMissingRid = false;
        try {
            collector.collect(ridlessSource, "students", ctx);
        } catch (QpException e) {
            threwMissingRid = (e.getErrorInfo().code() == ErrorCode.INVALID_STATE);
        }
        assertTrue(threwMissingRid, "MutationTargetCollector must throw QpException(INVALID_STATE) when RID is missing");

        // Wrong-table RID must trigger INVALID_VALUE (PRD §8)
        Operator wrongTableRidSource = new AbstractOperator(schema) {
            private boolean emitted = false;

            @Override
            protected void doOpen() {
                emitted = false;
            }

            @Override
            protected Optional<RowEnvelope> doNext() {
                if (emitted) {
                    return Optional.empty();
                }
                emitted = true;
                return Optional.of(RowEnvelope.of(
                        Row.of(List.of(intVal(1), strVal("Wrong"), intVal(20), floatVal(3.0))),
                        new RID(new PageId("other_table", 1L), 0)));
            }

            @Override
            protected void doClose() {
            }
        };
        boolean threwWrongTableRid = false;
        try {
            collector.collect(wrongTableRidSource, "students", ctx);
        } catch (QpException e) {
            threwWrongTableRid = (e.getErrorInfo().code() == ErrorCode.INVALID_VALUE);
        }
        assertTrue(threwWrongTableRid, "MutationTargetCollector must throw QpException(INVALID_VALUE) for foreign table RID");

        // Verify withQpStorage factory works seamlessly with StorageAdapter
        MutationTargetCollector qpCollector = MutationTargetCollector.withQpStorage(new StorageAdapter(storage));
        List<StoredRecord> viaAdapter = qpCollector.collect(scanOp, new TableId("students"), grantedContext(1051L));
        assertEquals(3, viaAdapter.size(), "MutationTargetCollector.withQpStorage must collect all 3 records");
    }

    // 6. DeleteExecutor Tests
    private static void testDeleteExecutor() {
        System.out.println("[TEST] DeleteExecutor (filtered delete, unfiltered delete, WRITE grant, one-shot)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema schema = studentSchema();
        TableScanOperator scanOp = new TableScanOperator(
                storage, new ScanSpec("students", Optional.empty(), Optional.empty()), schema);

        Expression colId = new Expression(
                ExpressionKind.COLUMN,
                Optional.empty(),
                Optional.of(new ColumnRef(Optional.of("students"), "id")),
                Optional.empty(),
                List.of());
        Expression lit2 = new Expression(
                ExpressionKind.LITERAL,
                Optional.of(intVal(2)),
                Optional.empty(),
                Optional.empty(),
                List.of());
        Expression predicate = new Expression(
                ExpressionKind.BINARY,
                Optional.empty(),
                Optional.empty(),
                Optional.of(ExpressionOperator.EQ),
                List.of(colId, lit2));

        FilterOperator filterOp = new FilterOperator(
                scanOp,
                new FilterSpec(predicate),
                new ExpressionEvaluator(new ValueComparator()),
                schema);

        MutationTargetCollector collector = new MutationTargetCollector(storage);
        var sequence = new AtomicLong();
        var concurrency = (sqlike.qp.port.QpContracts.IConcurrencyControl) java.lang.reflect.Proxy.newProxyInstance(
                QP3TestDriver.class.getClassLoader(), new Class<?>[]{sqlike.qp.port.QpContracts.IConcurrencyControl.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getState" -> sqlike.qp.port.QpContracts.TransactionState.ACTIVE;
                    case "authorizeChange" -> new sqlike.qp.port.QpContracts.WritePermit(
                            ((sqlike.qp.port.QpContracts.ChangeRecord) args[0]).id(), new sqlike.qp.port.QpContracts.Lsn(sequence.incrementAndGet()));
                    case "acquireApplyLease" -> (sqlike.qp.port.QpContracts.WriteLease) () -> { };
                    case "confirmApplied" -> null;
                    default -> throw new AssertionError("Unexpected CCM fixture call: " + method.getName());
                });
        MutationCoordinator mutations = new MutationCoordinator(storage, concurrency);
        DeleteExecutor deleteExec = new DeleteExecutor(
                filterOp,
                new DeleteSpec("students"),
                collector,
                mutations);

        ExecutionContext ctx = grantedContext(106L);
        long deletedCount = deleteExec.execute(ctx);
        assertEquals(1L, deletedCount, "DeleteExecutor should delete exactly 1 matching row (Bob)");
        assertEquals(2, storage.recordCount("students"), "2 records (Alice & Charlie) must remain in storage");

        boolean threwReExecute = false;
        try {
            deleteExec.execute(ctx);
        } catch (QpException e) {
            threwReExecute = (e.getErrorInfo().code() == ErrorCode.INVALID_STATE);
        }
        assertTrue(threwReExecute, "Re-executing DeleteExecutor must throw QpException(INVALID_STATE)");
        deleteExec.close();

        // PRD §10 step 2: DeleteExecutor must reject execution if context holds only READ grant
        ExecutionContext readOnlyCtx = ExecutionContext.of(1061L);
        readOnlyCtx.addGrant(new AccessGrant(
                new TxId(1061L),
                ResourceKey.table(new TableId("students")),
                AccessMode.READ));
        DeleteExecutor deniedDelete = new DeleteExecutor(
                scanOp,
                new DeleteSpec("students"),
                collector,
                mutations);
        boolean threwMissingWriteGrant = false;
        try {
            deniedDelete.execute(readOnlyCtx);
        } catch (QpException e) {
            threwMissingWriteGrant = (e.getErrorInfo().code() == ErrorCode.INVALID_STATE);
        } finally {
            deniedDelete.close();
        }
        assertTrue(threwMissingWriteGrant, "DeleteExecutor must reject READ-only context with ErrorCode.INVALID_STATE");

        // Unfiltered DELETE (without WHERE) deletes remaining 2 rows via logged delete per RID
        DeleteExecutor deleteAllExec = new DeleteExecutor(
                scanOp,
                new DeleteSpec("students"),
                collector,
                mutations);
        long deletedAll = deleteAllExec.execute(grantedContext(1062L));
        assertEquals(2L, deletedAll, "Unfiltered DELETE must delete all remaining 2 rows");
        assertEquals(0, storage.recordCount("students"), "Table must have 0 rows after unfiltered DELETE");
        deleteAllExec.close();
    }

    // Mock In-Memory IStorageManager for QP3 Standalone Testing
    private static final class InMemoryMockStorage implements IStorageManager {
        private final Map<String, Map<RID, Record>> tables = new LinkedHashMap<>();
        private final Map<String, List<RID>> indexLookupResults = new LinkedHashMap<>();
        private String missingTableErrorName;
        private String missingIndexErrorName;
        private String failCursorTableName;
        private boolean lastFailingCursorClosed;

        void putRecord(String table, Record record) {
            tables.computeIfAbsent(table, k -> new LinkedHashMap<>()).put(record.rid(), record);
        }

        void putIndexResults(String indexName, List<RID> rids) {
            indexLookupResults.put(indexName, List.copyOf(rids));
        }

        void setMissingTableError(String tableName) {
            this.missingTableErrorName = tableName;
        }

        void setMissingIndexError(String indexName) {
            this.missingIndexErrorName = indexName;
        }

        void setFailCursorOnTable(String tableName) {
            this.failCursorTableName = tableName;
            this.lastFailingCursorClosed = false;
        }

        boolean wasLastFailingCursorClosed() {
            return lastFailingCursorClosed;
        }

        int recordCount(String table) {
            return tables.getOrDefault(table, Map.of()).size();
        }

        @Override
        public RecordCursor scan(String table) {
            if (table.equals(missingTableErrorName)) {
                throw new StorageException(StorageErrorCode.TABLE_NOT_FOUND, "Table not found: " + table);
            }
            if (table.equals(failCursorTableName)) {
                return new RecordCursor() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public Record next() {
                        throw new UncheckedIOException("Simulated disk read error", new IOException("corrupt page"));
                    }

                    @Override
                    public void close() {
                        lastFailingCursorClosed = true;
                    }
                };
            }
            List<Record> snapshot = List.copyOf(tables.getOrDefault(table, Map.of()).values());
            Iterator<Record> it = snapshot.iterator();
            return new RecordCursor() {
                private boolean closed = false;

                @Override
                public boolean hasNext() {
                    return !closed && it.hasNext();
                }

                @Override
                public Record next() {
                    return it.next();
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }

        @Override
        public Optional<Record> fetch(String table, RID rid) {
            Map<RID, Record> tableMap = tables.get(table);
            if (tableMap == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(tableMap.get(rid));
        }

        @Override
        public void delete(String table, RID rid, long lsn) {
            if (lsn <= 0) throw new AssertionError("Unlogged delete");
            Map<RID, Record> tableMap = tables.get(table);
            if (tableMap != null) {
                tableMap.remove(rid);
            }
        }

        @Override
        public List<RID> lookupIndex(String name, IndexCondition condition) {
            if (name.equals(missingIndexErrorName)) {
                throw new StorageException(StorageErrorCode.INDEX_NOT_FOUND, "Index not found: " + name);
            }
            return indexLookupResults.getOrDefault(name, List.of());
        }

        @Override
        public InsertReservation reserveInsert(String table, List<Value> values) {
            throw new UnsupportedOperationException("Not used in QP3TestDriver");
        }

        @Override
        public RID insert(InsertReservation reservation, long lsn) {
            throw new UnsupportedOperationException("Not used in QP3TestDriver");
        }

        @Override
        public void update(String table, RID rid, List<Value> values, long lsn) {
            throw new UnsupportedOperationException("Not used in QP3TestDriver");
        }

        @Override
        public void releaseReservation(InsertReservation reservation) {
            throw new UnsupportedOperationException("Not used in QP3TestDriver");
        }

        @Override
        public void restoreRecord(String table, RID rid, RecordImage image, long lsn) {
            throw new UnsupportedOperationException("Not used in QP3TestDriver");
        }

        @Override
        public List<String> listTables() {
            return List.copyOf(tables.keySet());
        }

        @Override
        public TableSchema getSchema(String table) {
            if (!"students".equals(table) || !tables.containsKey(table)) {
                throw new StorageException(StorageErrorCode.TABLE_NOT_FOUND, "Unknown table: " + table);
            }
            return new TableSchema(table, table, studentSchema().columns());
        }

        @Override
        public TableStatistics getStatistics(String table) {
            throw new UnsupportedOperationException("Not used in QP3TestDriver");
        }

        @Override
        public void createIndex(IndexMetadata definition) {
            throw new UnsupportedOperationException("Not used in QP3TestDriver");
        }

        @Override
        public List<IndexMetadata> getIndexes(String table) {
            return List.of();
        }

        @Override
        public void flushAll() {
        }

        @Override
        public void close() {
        }
    }
}
