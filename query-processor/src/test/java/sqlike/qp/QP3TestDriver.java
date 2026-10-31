package sqlike.qp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlike.contract.IStorageManager;
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

public class QP3TestDriver {

    private static int testsRun = 0;
    private static int testsPassed = 0;

    public static void main(String[] args) {
        System.out.println("SQLike QP3 (Row Operators & Delete) Tests      ");

        try {
            testTableScanOperator();
            testIndexScanOperator();
            testFilterOperator();
            testProjectOperator();
            testMutationTargetCollector();
            testDeleteExecutor();
            System.out.println("RESULT: All " + testsPassed + " / " + testsRun + " tests PASSED successfully!");
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
        System.out.println("[TEST] TableScanOperator (scan, rescan, empty, lifecycle)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema schema = studentSchema();
        ScanSpec spec = new ScanSpec("students", Optional.empty(), Optional.empty());
        TableScanOperator scanOp = new TableScanOperator(storage, spec, schema);
        ExecutionContext ctx = ExecutionContext.of(101L);

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
    }

    // 2. IndexScanOperator Tests
    private static void testIndexScanOperator() {
        System.out.println("[TEST] IndexScanOperator (lookupIndex, fetchRecord, missing RID)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema schema = studentSchema();
        IndexCondition cond = new IndexCondition(
                IndexOperator.GE, intVal(21), Optional.empty(), true, false);
        IndexScanSpec spec = new IndexScanSpec("students", "idx_students_age", Optional.of(cond));
        IndexScanOperator indexOp = new IndexScanOperator(storage, spec, schema);
        ExecutionContext ctx = ExecutionContext.of(102L);

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
    }

    // 3. FilterOperator Tests
    private static void testFilterOperator() {
        System.out.println("[TEST] FilterOperator (predicate filtering & RID preservation)...");
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
        FilterOperator filterOp = new FilterOperator(scanOp, new FilterSpec(predicate), evaluator, schema);
        ExecutionContext ctx = ExecutionContext.of(103L);

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
    }

    // 4. ProjectOperator Tests
    private static void testProjectOperator() {
        System.out.println("[TEST] ProjectOperator (column projection & RID preservation)...");
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
        ExecutionContext ctx = ExecutionContext.of(104L);

        projectOp.open(ctx);
        Optional<RowEnvelope> first = projectOp.next();
        assertTrue(first.isPresent(), "Projected first row should be present");
        assertEquals(2, first.get().row().size(), "Projected row must have 2 columns");
        assertEquals(strVal("Alice"), first.get().row().getValue(0), "Column 0 must be projected 'name'");
        assertEquals(intVal(1), first.get().row().getValue(1), "Column 1 must be projected 'id'");
        assertTrue(first.get().rid().isPresent(), "ProjectOperator must preserve source RID");
        projectOp.close();
    }

    // 5. MutationTargetCollector Tests
    private static void testMutationTargetCollector() {
        System.out.println("[TEST] MutationTargetCollector (materialization & missing RID error)...");
        InMemoryMockStorage storage = seedStudentStorage();
        Schema schema = studentSchema();
        TableScanOperator scanOp = new TableScanOperator(
                storage, new ScanSpec("students", Optional.empty(), Optional.empty()), schema);

        MutationTargetCollector collector = new MutationTargetCollector(storage);
        ExecutionContext ctx = ExecutionContext.of(105L);

        List<StoredRecord> collected = collector.collect(scanOp, "students", ctx);
        assertEquals(3, collected.size(), "Collector must materialize all 3 records from scan");
        assertEquals(OperatorState.CLOSED, scanOp.getState(), "Collector must close source operator in finally block");

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
    }

    // 6. DeleteExecutor Tests
    private static void testDeleteExecutor() {
        System.out.println("[TEST] DeleteExecutor (filtered delete & one-shot execution)...");
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
        MutationCoordinator mutations = new MutationCoordinator(storage);
        DeleteExecutor deleteExec = new DeleteExecutor(
                filterOp,
                new DeleteSpec("students"),
                collector,
                mutations);

        ExecutionContext ctx = ExecutionContext.of(106L);
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
    }

    // Mock In-Memory IStorageManager for QP3 Standalone Testing
    private static final class InMemoryMockStorage implements IStorageManager {
        private final Map<String, Map<RID, Record>> tables = new LinkedHashMap<>();
        private final Map<String, List<RID>> indexLookupResults = new LinkedHashMap<>();

        void putRecord(String table, Record record) {
            tables.computeIfAbsent(table, k -> new LinkedHashMap<>()).put(record.rid(), record);
        }

        void putIndexResults(String indexName, List<RID> rids) {
            indexLookupResults.put(indexName, List.copyOf(rids));
        }

        int recordCount(String table) {
            return tables.getOrDefault(table, Map.of()).size();
        }

        @Override
        public RecordCursor scan(String table) {
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
            Map<RID, Record> tableMap = tables.get(table);
            if (tableMap != null) {
                tableMap.remove(rid);
            }
        }

        @Override
        public List<RID> lookupIndex(String name, IndexCondition condition) {
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
            throw new UnsupportedOperationException("Not used in QP3TestDriver");
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
    }
}
