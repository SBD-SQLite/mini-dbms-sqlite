package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import sqlike.qo.PlanNodes;
import sqlike.qp.port.QpContracts.CancellationToken;
import sqlike.qp.port.StorageAdapter;
import sqlike.contract.IStorageManager;
import sqlike.shared.ColumnRef;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.ExpressionOperator;
import sqlike.shared.JoinAlgorithm;
import sqlike.shared.JoinSpec;
import sqlike.shared.JoinType;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.SortDirection;
import sqlike.shared.SortSpec;
import sqlike.shared.Value;
import sqlike.shared.PlanNode;
import sqlike.shared.QueryPlan;
import sqlike.shared.RecordCursor;
import sqlike.shared.ResultKind;
import sqlike.shared.TableSchema;

/**
 * QP4 verification driver testing both real BNLJ / Sort execution and lifecycle contracts.
 */
public class QP4TestDriver {
    private static final IValueComparator VALUES = new ValueComparator();
    private static final IExpressionEvaluator EVALUATOR = new ExpressionEvaluator(VALUES);
    private static final ExecutionContext CONTEXT = ExecutionContext.of(41, "qp4-test");
    private static int scenarios;
    private static int checks;

    public static void main(String[] args) {
        run("layout concatenation, output mapping and validation", QP4TestDriver::layout);
        run("comparator direction, numeric promotion and ties", QP4TestDriver::comparator);
        run("logical estimator and UTF-8 byte sizes", QP4TestDriver::estimator);
        run("explicit Cartesian/theta/natural fixtures and rescan", QP4TestDriver::joins);
        run("empty inputs, invalid state and fixture ownership", QP4TestDriver::lifecycle);
        run("ASC/DESC/default sort fixtures and RID preservation", QP4TestDriver::sorts);
        run("QP3 filter/project composition and three-table lifecycle", QP4TestDriver::composition);
        run("partial open and cleanup errors", QP4TestDriver::failures);
        run("real BNLJ algorithm (Cartesian, Theta, Natural, multi-batch, 3-table)", QP4TestDriver::realBnlj);
        run("real SortOperator materialization and sorting (ASC, DESC, ties, RID)", QP4TestDriver::realSort);
        run("Unicode code-point collation in real sorting", QP4TestDriver::unicodeSort);
        run("exact buffer limits, oversized rows and budget lifecycle", QP4TestDriver::bufferLimits);
        run("cooperative cancellation and cleanup", QP4TestDriver::cancellation);
        run("configured translator/facade and hidden sort key", QP4TestDriver::configuredPlans);
        run("repeatable right subtree and natural join edge cases", QP4TestDriver::rightSubtree);
        run("duplicate matches on both inputs across batch sizes", QP4TestDriver::duplicateInputs);
        run("cursor failures during inner rescan propagate and clean up", QP4TestDriver::rescanFailures);
        System.out.println("QP4: " + scenarios + "/17 scenarios, " + checks + " checks PASS");
    }

    private static void layout() {
        Schema left = schema("A", "id", "amount");
        Schema right = schema("B", "id", "value");
        Schema output = schema(null, "id", "value");
        List<Integer> mapping = new ArrayList<>(List.of(0, 3));
        JoinLayout layout = new JoinLayout(left, right, output, mapping);
        mapping.set(0, 1);
        check(layout.getInputSchema().columnCount() == 4, "predicate schema concatenates both children");
        check(layout.getInputSchema().findColumnOffset("A", "id") == 0, "left table prefix resolves to left offset");
        check(layout.getInputSchema().findColumnOffset("B", "id") == 2, "right table prefix resolves to right offset");
        check(layout.getOutputMapping().equals(List.of(0, 3)), "mapping copied defensively");
        RowEnvelope a = RowEnvelope.of(row(1, 10), new RID(new PageId("A", 0), 0));
        RowEnvelope b = RowEnvelope.of(row(1, 20), new RID(new PageId("B", 0), 0));
        check(layout.combineForPredicate(a, b).equals(row(1, 10, 1, 20)), "predicate row concatenation");
        check(layout.combineForOutput(a, b).row().equals(row(1, 20)), "output mapping");
        check(layout.combineForOutput(a, b).rid().isEmpty(), "join result has no single physical RID");
        expect(UnsupportedOperationException.class, () -> layout.getOutputMapping().add(1));
        expect(IllegalArgumentException.class, () -> new JoinLayout(left, right, output, List.of(0)));
        expect(IllegalArgumentException.class, () -> new JoinLayout(left, right, output, List.of(0, 9)));
        Schema textOutput = Schema.of(List.of(new ColumnSchema("text", DataType.VARCHAR, Optional.of(20))));
        expect(IllegalArgumentException.class, () -> new JoinLayout(left, right, textOutput, List.of(0)));
        expect(IllegalArgumentException.class, () -> layout.combineForPredicate(RowEnvelope.of(row(1)), b));
    }

    private static void comparator() {
        Schema schema = schema("T", "key");
        RowComparator asc = new RowComparator(schema, ColumnRef.of("T", "key"), null, VALUES);
        RowComparator desc = new RowComparator(schema, ColumnRef.of("T", "key"), SortDirection.DESC, VALUES);
        check(asc.getDirection() == SortDirection.ASC && asc.getKeyOffset() == 0, "default ASC and key offset");
        check(asc.compare(envelope(2), envelope(10)) < 0, "numeric values compared numerically");
        check(desc.compare(envelope(2), envelope(10)) > 0, "DESC reverses comparison");
        check(asc.compare(envelope(2), envelope(2)) == 0, "tie returns zero");
        Value floating = new Value(DataType.FLOAT, Optional.empty(), Optional.of(2.0), Optional.empty());
        check(asc.compare(envelope(2), RowEnvelope.of(Row.of(floating))) == 0, "INTEGER/FLOAT promotion follows QP2");
        expect(IllegalArgumentException.class, () -> new RowComparator(schema, ColumnRef.of("T", "missing"), SortDirection.ASC, VALUES));
        IValueComparator extreme = new IValueComparator() {
            @Override public int compare(Value left, Value right) { return Integer.MIN_VALUE; }
            @Override public boolean equal(Value left, Value right) { return false; }
        };
        check(new RowComparator(schema, ColumnRef.of("T", "key"), SortDirection.DESC, extreme).compare(envelope(1), envelope(2)) > 0,
                "DESC avoids Integer.MIN_VALUE negation overflow");
    }

    private static void estimator() {
        RowSizeEstimator estimator = new RowSizeEstimator();
        check(estimator.estimate(envelope(10)) == 24, "INTEGER row formula");
        Value floating = new Value(DataType.FLOAT, Optional.empty(), Optional.of(2.5), Optional.empty());
        Value text = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of("\u00e9"));
        RowEnvelope mixed = RowEnvelope.of(Row.of(integer(1), floating, text));
        check(estimator.estimate(mixed) == 46, "tags and UTF-8 text bytes included");
        check(estimator.estimate(RowEnvelope.of(mixed.row(), new RID(new PageId("T", 0), 0))) == 62, "logical RID overhead");
        check(estimator.estimate(RowEnvelope.of(Row.of())) == 16, "empty row is still a row");
        Value invalid = new Value(DataType.FLOAT, Optional.empty(), Optional.of(Double.NaN), Optional.empty());
        expect(IllegalArgumentException.class, () -> estimator.estimate(RowEnvelope.of(Row.of(invalid))));
    }

    private static void joins() {
        Schema a = schema("A", "id", "amount");
        Schema b = schema("B", "id", "value");
        JoinSpec cartesian = spec(JoinType.CARTESIAN, List.of());
        exerciseJoin(a, b, schema(null, "aid", "amount", "bid", "value"), List.of(0, 1, 2, 3), cartesian,
                List.of(RowEnvelope.of(row(1, 10, 2, 20)), RowEnvelope.of(row(1, 10, 2, 20))));
        Expression condition = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(), Optional.of(ExpressionOperator.LT),
                List.of(column("A", "id"), column("B", "id")));
        JoinSpec theta = new JoinSpec(JoinType.THETA, Optional.of(condition), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
        exerciseJoin(a, b, schema(null, "aid", "bid"), List.of(0, 2), theta, List.of(RowEnvelope.of(row(1, 2))));
        exerciseJoin(a, b, schema(null, "id", "amount", "value"), List.of(0, 1, 3), spec(JoinType.NATURAL, List.of("id")),
                List.of(RowEnvelope.of(row(1, 10, 20))));
        exerciseJoin(a, b, schema(null, "aid", "amount", "bid", "value"), List.of(0, 1, 2, 3), spec(JoinType.NATURAL, List.of()),
                List.of(RowEnvelope.of(row(1, 10, 2, 20))));
        exerciseJoin(schema("A", "id", "group", "amount"), schema("B", "id", "group", "value"),
                schema(null, "id", "group", "amount", "value"), List.of(0, 1, 2, 5), spec(JoinType.NATURAL, List.of("id", "group")),
                List.of(RowEnvelope.of(row(1, 4, 10, 20))));
    }

    private static void exerciseJoin(Schema a, Schema b, Schema output, List<Integer> mapping, JoinSpec spec, List<RowEnvelope> expected) {
        Probe left = new Probe(a);
        Probe right = new Probe(b);
        BlockNestedLoopJoinOperator join = join(left, right, output, mapping, spec);
        join.setMockRows(expected);
        check(join.schema().equals(output) && join.getSpec().equals(spec), "schema/spec available before open");
        for (int pass = 0; pass < 2; pass++) {
            join.open(CONTEXT);
            checkBag(drain(join).stream().map(RowEnvelope::row).toList(), expected.stream().map(RowEnvelope::row).toList(), "mock preserves registered rows and duplicates");
            join.close();
            join.close();
            check(left.opens == pass + 1 && right.opens == pass + 1, "children reopened for rescan");
            check(left.closes == pass + 1 && right.closes == pass + 1, "close is idempotent");
            check(left.lastContext == CONTEXT && right.lastContext == CONTEXT, "same transaction context forwarded");
        }
        check(left.nextCalls == 0 && right.nextCalls == 0, "M1 fixture does not secretly execute BNLJ");
    }

    private static void lifecycle() {
        Schema a = schema("A", "id");
        Schema b = schema("B", "value");
        BlockNestedLoopJoinOperator join = join(new Probe(a), new Probe(b), schema(null, "id", "value"), List.of(0, 1), spec(JoinType.CARTESIAN, List.of()));
        expect(QpException.class, join::next);
        join.open(CONTEXT);
        check(drain(join).isEmpty(), "empty children yield empty join result");
        join.close();
        List<RowEnvelope> supplied = new ArrayList<>(List.of(RowEnvelope.of(row(1, 2))));
        join.setMockRows(supplied);
        supplied.clear();
        join.open(CONTEXT);
        expect(QpException.class, () -> join.open(CONTEXT));
        expect(QpException.class, () -> join.setMockRows(List.of()));
        check(drain(join).size() == 1, "fixture list is copied");
        join.close();
        expect(QpException.class, join::next);
        expect(IllegalArgumentException.class, () -> join.setMockRows(List.of(envelope(1))));
        expect(IllegalArgumentException.class, () -> join.setMockRows(List.of(RowEnvelope.of(row(1, 2), new RID(new PageId("A", 0), 1)))));
        join.setMockRows(List.of());
        join.open(CONTEXT);
        check(join.schema().columnCount() == 2 && drain(join).isEmpty(), "empty result retains header");
        join.close();
        Probe one = new Probe(a);
        expect(IllegalArgumentException.class, () -> join(one, one, a, List.of(0), spec(JoinType.CARTESIAN, List.of())));
        Schema empty = Schema.of(List.of());
        BlockNestedLoopJoinOperator zeroColumn = join(new Probe(empty), new Probe(empty), empty, List.of(), spec(JoinType.CARTESIAN, List.of()));
        zeroColumn.setMockRows(List.of(RowEnvelope.of(Row.of())));
        zeroColumn.open(CONTEXT);
        check(zeroColumn.next().orElseThrow().row().size() == 0 && zeroColumn.next().isEmpty(), "zero-column row differs from END");
        zeroColumn.close();
    }

    private static void sorts() {
        Schema schema = schema("T", "key");
        for (SortDirection direction : new SortDirection[] {null, SortDirection.ASC, SortDirection.DESC}) {
            Probe child = new Probe(schema);
            SortOperator sort = sort(child, direction);
            sort.open(CONTEXT);
            check(drain(sort).isEmpty(), "empty child sort yields empty result");
            sort.close();
            child.opens = 0;
            child.closes = 0;
            child.nextCalls = 0;
            RowEnvelope marked = RowEnvelope.of(row(2), new RID(new PageId("T", 0), 1));
            List<RowEnvelope> fixture = direction == SortDirection.DESC
                    ? List.of(envelope(10), marked, envelope(2), envelope(-3))
                    : List.of(envelope(-3), marked, envelope(2), envelope(10));
            sort.setMockRows(fixture);
            for (int pass = 0; pass < 2; pass++) {
                sort.open(CONTEXT);
                expect(QpException.class, () -> sort.setMockRows(fixture));
                List<RowEnvelope> result = drain(sort);
                check(result.equals(fixture) && result.get(1) == marked, "sort mock preserves row, duplicates and RID");
                sort.close();
                sort.close();
            }
            check(child.opens == 2 && child.closes == 2 && child.nextCalls == 0, "sort uses fixture when setMockRows is registered");
            sort.setMockRows(List.of());
            sort.open(CONTEXT);
            check(drain(sort).isEmpty() && sort.schema().equals(schema), "empty sort header");
            sort.close();
            List<RowEnvelope> wrongOrder = direction == SortDirection.DESC ? List.of(envelope(1), envelope(2)) : List.of(envelope(2), envelope(1));
            expect(IllegalArgumentException.class, () -> sort.setMockRows(wrongOrder));
            expect(IllegalArgumentException.class, () -> sort.setMockRows(List.of(RowEnvelope.of(row(1, 2)))));
        }
    }

    private static void composition() {
        Probe a = new Probe(schema("A", "id", "amount"));
        Probe b = new Probe(schema("B", "id"));
        Schema joined = schema(null, "aid", "amount", "bid");
        BlockNestedLoopJoinOperator join = join(a, b, joined, List.of(0, 1, 2), spec(JoinType.CARTESIAN, List.of()));
        join.setMockRows(List.of(RowEnvelope.of(row(1, 5, 7)), RowEnvelope.of(row(2, 20, 8))));
        Expression predicate = new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(), Optional.of(ExpressionOperator.GT),
                List.of(column(null, "amount"), literal(10)));
        FilterOperator filter = new FilterOperator(join, new FilterSpec(predicate), EVALUATOR, joined);
        Schema projected = schema(null, "bid", "aid");
        ProjectOperator project = new ProjectOperator(filter, new ProjectSpec(List.of(ColumnRef.of(null, "bid"), ColumnRef.of(null, "aid"))), projected);
        project.open(CONTEXT);
        checkBag(drain(project).stream().map(RowEnvelope::row).toList(), List.of(row(8, 2)), "real QP3 filter/project consumes join fixture");
        project.close();
        Probe c = new Probe(schema("C", "code"));
        Schema three = schema(null, "aid", "amount", "bid", "code");
        BlockNestedLoopJoinOperator nested = join(join, c, three, List.of(0, 1, 2, 3), spec(JoinType.CARTESIAN, List.of()));
        nested.setMockRows(List.of(RowEnvelope.of(row(2, 20, 8, 9))));
        for (int pass = 0; pass < 2; pass++) {
            nested.open(CONTEXT);
            check(drain(nested).size() == 1, "three-table fixture lifecycle");
            nested.close();
        }
        check(a.nextCalls == 0 && b.nextCalls == 0 && c.nextCalls == 0, "composition does not execute production join when fixture set");
    }

    private static void failures() {
        Probe left = new Probe(schema("A", "id"));
        Probe right = new Probe(schema("B", "value"));
        right.failOpen = true;
        BlockNestedLoopJoinOperator join = join(left, right, schema(null, "id", "value"), List.of(0, 1), spec(JoinType.CARTESIAN, List.of()));
        join.setMockRows(List.of());
        expect(IllegalStateException.class, () -> join.open(CONTEXT));
        check(left.closes == 1 && right.closes == 1, "partial join open cleans both children");
        join.close();
        check(left.closes == 1 && right.closes == 1, "failed open cleanup can be closed again");
        right.failOpen = false;
        left.failClose = true;
        right.failClose = true;
        join.open(CONTEXT);
        try {
            join.close();
            throw new AssertionError("close error expected");
        } catch (IllegalStateException error) {
            check(error.getSuppressed().length == 1, "both cleanup errors retained");
        }
        check(left.closes == 2 && right.closes == 2 && join.getState() == OperatorState.CLOSED, "both children closed on failure");
        Probe child = new Probe(schema("T", "key"));
        child.failOpen = true;
        child.failClose = true;
        SortOperator sort = sort(child, SortDirection.ASC);
        sort.setMockRows(List.of());
        try {
            sort.open(CONTEXT);
            throw new AssertionError("sort open error expected");
        } catch (IllegalStateException error) {
            check(error.getSuppressed().length == 1, "sort preserves open error and cleanup error");
        }
        sort.close();
        check(child.closes == 1, "partial sort open cleanup is idempotent");
    }

    private static void realBnlj() {
        Schema aSchema = schema("A", "id", "amount");
        Schema bSchema = schema("B", "id", "value");
        List<RowEnvelope> aRows = List.of(
                RowEnvelope.of(row(1, 100), new RID(new PageId("A", 0), 0)),
                RowEnvelope.of(row(2, 200), new RID(new PageId("A", 0), 1)),
                RowEnvelope.of(row(3, 300), new RID(new PageId("A", 0), 2)));
        List<RowEnvelope> bRows = List.of(
                RowEnvelope.of(row(2, 20), new RID(new PageId("B", 0), 0)),
                RowEnvelope.of(row(3, 30), new RID(new PageId("B", 0), 1)),
                RowEnvelope.of(row(4, 40), new RID(new PageId("B", 0), 2)));

        // 1. Real THETA JOIN (A.id = B.id) with multi-batch outer buffer
        Probe leftTheta = new Probe(aSchema, aRows);
        Probe rightTheta = new Probe(bSchema, bRows);
        Expression eqCond = new Expression(
                ExpressionKind.BINARY,
                Optional.empty(),
                Optional.empty(),
                Optional.of(ExpressionOperator.EQ),
                List.of(column("A", "id"), column("B", "id")));
        JoinSpec thetaSpec = new JoinSpec(JoinType.THETA, Optional.of(eqCond), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
        Schema thetaOut = schema(null, "id", "amount", "id", "value");
        BlockNestedLoopJoinOperator thetaJoin = join(leftTheta, rightTheta, thetaOut, List.of(0, 1, 2, 3), thetaSpec);
        thetaJoin.setMaxOuterBytes(50); // force multiple outer batches (each 2-int row with RID is 48 bytes)
        thetaJoin.open(CONTEXT);
        List<Row> thetaResult = drain(thetaJoin).stream().map(RowEnvelope::row).toList();
        thetaJoin.close();
        checkBag(thetaResult, List.of(row(2, 200, 2, 20), row(3, 300, 3, 30)), "real THETA join matches A.id = B.id");
        check(rightTheta.opens == 3, "multi-batch BNLJ rescans inner relation once per outer batch");

        // 2. Real NATURAL JOIN on common column "id"
        Probe leftNat = new Probe(aSchema, aRows);
        Probe rightNat = new Probe(bSchema, bRows);
        JoinSpec natSpec = spec(JoinType.NATURAL, List.of("id"));
        Schema natOut = schema(null, "id", "amount", "value");
        BlockNestedLoopJoinOperator natJoin = join(leftNat, rightNat, natOut, List.of(0, 1, 3), natSpec);
        natJoin.open(CONTEXT);
        List<Row> natResult = drain(natJoin).stream().map(RowEnvelope::row).toList();
        natJoin.close();
        checkBag(natResult, List.of(row(2, 200, 20), row(3, 300, 30)), "real NATURAL join deduplicates id and matches values");
        check(natJoin.schema().findColumnOffset("A", "id") == 0 && natJoin.schema().findColumnOffset("B", "id") == 0,
                "NATURAL join common column accessible via both A.id and B.id");

        // 3. Real 3-table JOIN (A JOIN B JOIN C)
        Schema cSchema = schema("C", "id", "bonus");
        List<RowEnvelope> cRows = List.of(RowEnvelope.of(row(3, 5)));
        Probe cProbe = new Probe(cSchema, cRows);
        Expression bcCond = new Expression(
                ExpressionKind.BINARY,
                Optional.empty(),
                Optional.empty(),
                Optional.of(ExpressionOperator.EQ),
                List.of(column("B", "id"), column("C", "id")));
        JoinSpec bcSpec = new JoinSpec(JoinType.THETA, Optional.of(bcCond), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
        Schema threeOut = schema(null, "id", "amount", "value", "cid", "bonus");
        BlockNestedLoopJoinOperator threeJoin = join(natJoin, cProbe, threeOut, List.of(0, 1, 2, 3, 4), bcSpec);
        threeJoin.open(CONTEXT);
        List<Row> threeResult = drain(threeJoin).stream().map(RowEnvelope::row).toList();
        threeJoin.close();
        checkBag(threeResult, List.of(row(3, 300, 30, 3, 5)), "real 3-table join produces correct tuple");
    }

    private static void realSort() {
        Schema schema = schema("T", "key", "seq");
        RowEnvelope r1 = RowEnvelope.of(row(10, 1), new RID(new PageId("T", 0), 1));
        RowEnvelope r2 = RowEnvelope.of(row(2, 2), new RID(new PageId("T", 0), 2));
        RowEnvelope r3 = RowEnvelope.of(row(-5, 3), new RID(new PageId("T", 0), 3));
        RowEnvelope r4 = RowEnvelope.of(row(2, 4), new RID(new PageId("T", 0), 4));
        List<RowEnvelope> unsorted = List.of(r1, r2, r3, r4);

        // ASC sort
        Probe childAsc = new Probe(schema, unsorted);
        ColumnRef col = ColumnRef.of("T", "key");
        SortOperator sortAsc = new SortOperator(
                childAsc, new SortSpec(col, SortDirection.ASC), new RowComparator(schema, col, SortDirection.ASC, VALUES), schema);
        sortAsc.open(CONTEXT);
        List<RowEnvelope> ascResult = drain(sortAsc);
        sortAsc.close();
        check(ascResult.equals(List.of(r3, r2, r4, r1)), "real ASC sort orders rows and preserves tie stability + RIDs");

        // DESC sort
        Probe childDesc = new Probe(schema, unsorted);
        SortOperator sortDesc = new SortOperator(
                childDesc, new SortSpec(col, SortDirection.DESC), new RowComparator(schema, col, SortDirection.DESC, VALUES), schema);
        sortDesc.open(CONTEXT);
        List<RowEnvelope> descResult = drain(sortDesc);
        sortDesc.close();
        check(descResult.equals(List.of(r1, r2, r4, r3)), "real DESC sort orders rows in descending order");
    }

    private static void unicodeSort() {
        Schema schema = Schema.of("T", List.of(new ColumnSchema("key", DataType.VARCHAR, Optional.of(20))));
        List<String> expected = List.of("", "a", "aa", "\ue000", "\ud800\udc00", "\ud800\udc00x");
        List<RowEnvelope> input = List.of(expected.get(5), expected.get(3), expected.get(1), expected.get(4), expected.get(0), expected.get(2))
                .stream().map(text -> RowEnvelope.of(Row.of(new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of(text))))).toList();
        for (SortDirection direction : SortDirection.values()) {
            SortOperator sort = sort(new Probe(schema, input), direction);
            sort.open(CONTEXT);
            List<String> actual = drain(sort).stream().map(r -> r.row().getValue(0).textValue().orElseThrow()).toList();
            sort.close();
            List<String> ordered = new ArrayList<>(expected);
            if (direction == SortDirection.DESC) {java.util.Collections.reverse(ordered);}
            check(actual.equals(ordered), "text order follows code points, including supplementary characters and prefixes");
        }
    }

    private static void bufferLimits() {
        Schema a = schema("A", "id");
        Schema b = schema("B", "id");
        Probe left = new Probe(a, List.of(envelope(1), envelope(2), envelope(3)));
        Probe right = new Probe(b, List.of(envelope(9)));
        BlockNestedLoopJoinOperator join = join(left, right, schema(null, "id", "id"), List.of(0, 1), spec(JoinType.CARTESIAN, List.of()));
        expect(IllegalArgumentException.class, () -> join.setMaxOuterBytes(0));
        expect(IllegalArgumentException.class, () -> join.setMaxOuterBytes(-1));
        join.setMaxOuterBytes(48);
        join.open(CONTEXT);
        expect(QpException.class, () -> join.setMaxOuterBytes(100));
        checkBag(drain(join).stream().map(RowEnvelope::row).toList(), List.of(row(1, 9), row(2, 9), row(3, 9)), "exact-fit batch preserves pending row");
        check(right.opens == 2 && right.lastContext == CONTEXT, "inner rescan keeps the execution context");
        join.close();
        join.setMaxOuterBytes(23);
        try {
            join.open(CONTEXT);
            throw new AssertionError("Oversized outer row accepted");
        } catch (QpException error) {
            check(error.getErrorInfo().code() == sqlike.shared.ErrorCode.INVALID_VALUE, "oversized row reports INVALID_VALUE");
            check(left.closes == 2 && right.closes == 3, "oversized-row failure closes both children");
        } finally {
            join.close();
        }
        join.setMaxOuterBytes(24);
        join.open(CONTEXT);
        checkBag(drain(join).stream().map(RowEnvelope::row).toList(), List.of(row(1, 9), row(2, 9), row(3, 9)), "single-row exact-fit batches work after failure and close");
        join.close();
    }

    private static void cancellation() {
        CancellationToken token = new CancellationToken();
        ExecutionContext context = new ExecutionContext(42, Optional.empty(), true, new AtomicLong(), token);
        Probe child = new Probe(schema("T", "key"), List.of(envelope(3), envelope(2), envelope(1)));
        SortOperator sort = sort(child, SortDirection.ASC);
        token.cancel();
        expect(RuntimeException.class, () -> sort.open(context));
        check(child.opens == 0 && child.nextCalls == 0, "pre-cancelled sort does not consume its child");
        sort.close();

        CancellationToken readingToken = new CancellationToken();
        ExecutionContext readingContext = new ExecutionContext(43, Optional.empty(), true, new AtomicLong(), readingToken);
        child.onNext = () -> {if (child.nextCalls == 2) {readingToken.cancel();}};
        expect(RuntimeException.class, () -> sort.open(readingContext));
        check(child.nextCalls == 2 && child.closes == 1, "materialization stops promptly and closes its child");
        sort.close();

        CancellationToken sortingToken = new CancellationToken();
        ExecutionContext sortingContext = new ExecutionContext(44, Optional.empty(), true, new AtomicLong(), sortingToken);
        RowComparator cancellingComparator = new RowComparator(child.schema(), ColumnRef.of("T", "key"), SortDirection.ASC, VALUES) {
            @Override public int compare(RowEnvelope left, RowEnvelope right) {
                sortingToken.cancel();
                return super.compare(left, right);
            }
        };
        Probe sortingChild = new Probe(child.schema(), List.of(envelope(2), envelope(1)));
        SortOperator cancelledSort = new SortOperator(sortingChild, new SortSpec(ColumnRef.of("T", "key"), SortDirection.ASC), cancellingComparator, child.schema());
        expect(RuntimeException.class, () -> cancelledSort.open(sortingContext));
        check(sortingChild.closes == 1, "cancellation during final comparison still fails and cleans up");
        cancelledSort.close();

        CancellationToken outputToken = new CancellationToken();
        SortOperator outputSort = sort(new Probe(child.schema(), List.of(envelope(1))), SortDirection.ASC);
        outputSort.open(new ExecutionContext(48, Optional.empty(), true, new AtomicLong(), outputToken));
        outputToken.cancel();
        expect(RuntimeException.class, outputSort::next);
        outputSort.close();

        Probe left = new Probe(schema("A", "id"), List.of(envelope(1), envelope(2)));
        Probe right = new Probe(schema("B", "id"), List.of(envelope(1)));
        Schema output = schema(null, "id", "id");
        BlockNestedLoopJoinOperator join = join(left, right, output, List.of(0, 1), spec(JoinType.CARTESIAN, List.of()));
        expect(RuntimeException.class, () -> join.open(context));
        check(left.opens == 0 && right.opens == 0, "pre-cancelled join does not open children");
        join.close();
        CancellationToken joinToken = new CancellationToken();
        ExecutionContext joinContext = new ExecutionContext(45, Optional.empty(), true, new AtomicLong(), joinToken);
        join.open(joinContext);
        joinToken.cancel();
        expect(RuntimeException.class, join::next);
        join.close();
        check(left.closes == 1 && right.closes == 1, "cancelled join closes both children");

        CancellationToken matchingToken = new CancellationToken();
        int[] comparisons = {0};
        IExpressionEvaluator evaluator = new ExpressionEvaluator(VALUES) {
            @Override public boolean evaluateCondition(Expression expression, Row row, Schema schema) {
                comparisons[0]++;
                matchingToken.cancel();
                return false;
            }
        };
        JoinSpec theta = new JoinSpec(JoinType.THETA, Optional.of(relation(ExpressionOperator.EQ, column("A", "id"), column("B", "id"))), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
        BlockNestedLoopJoinOperator matching = new BlockNestedLoopJoinOperator(left, right, theta, new JoinLayout(left.schema(), right.schema(), output, List.of(0, 1)), evaluator, VALUES, new RowSizeEstimator(), output);
        matching.open(new ExecutionContext(46, Optional.empty(), true, new AtomicLong(), matchingToken));
        expect(RuntimeException.class, matching::next);
        matching.close();
        check(comparisons[0] == 1, "nonmatching pairs check cancellation within a batch");

        CancellationToken fillingToken = new CancellationToken();
        Probe fillingChild = new Probe(left.schema(), List.of(envelope(1), envelope(2), envelope(3)));
        fillingChild.onNext = () -> {if (fillingChild.nextCalls == 2) {fillingToken.cancel();}};
        BlockNestedLoopJoinOperator filling = join(fillingChild, right, output, List.of(0, 1), spec(JoinType.CARTESIAN, List.of()));
        expect(RuntimeException.class, () -> filling.open(new ExecutionContext(49, Optional.empty(), true, new AtomicLong(), fillingToken)));
        check(fillingChild.nextCalls == 2 && fillingChild.closes == 1, "outer materialization stops and closes on cancellation");
        filling.close();
    }

    private static void configuredPlans() {
        IStorageManager storage = readOnlyStorage();
        QpConfiguration defaults = QpConfiguration.defaults();
        QpConfiguration tiny = new QpConfiguration(0, 2, 16, defaults.defaultIndexKind(), defaults.temporaryDirectory());
        PlanNode joined = PlanNodes.join(PlanNodes.scan("A"), PlanNodes.scan("B"),
                new JoinSpec(JoinType.THETA, Optional.of(relation(ExpressionOperator.LT, column("A", "id"), column("B", "id"))), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of()));
        PlanTranslator translator = new PlanTranslator(storage, null, VALUES, null, null, null, null, tiny);
        BlockNestedLoopJoinOperator join = (BlockNestedLoopJoinOperator) translator.translateSelect(joined);
        check(join.getMaxOuterBytes() == 16, "public storage translator applies configured buffer");
        expect(QpException.class, () -> join.open(CONTEXT));
        join.close();
        PlanTranslator adapted = PlanTranslator.withQpStorage(new StorageAdapter(storage), null, VALUES, null, null, null, null, tiny);
        check(((BlockNestedLoopJoinOperator) adapted.translateSelect(joined)).getMaxOuterBytes() == 16, "adapted storage translator applies configured buffer");
        PlanTranslator legacy = new PlanTranslator(storage, null, VALUES, null, null, null, null);
        check(((BlockNestedLoopJoinOperator) legacy.translateSelect(joined)).getMaxOuterBytes() == 65536, "legacy constructor retains 64 KiB default");
        PlanNode projected = PlanNodes.project(PlanNodes.sort(joined, new SortSpec(ColumnRef.of("B", "id"), SortDirection.DESC)), List.of(ColumnRef.of("A", "id")));
        Operator pipeline = legacy.translateSelect(projected);
        pipeline.open(CONTEXT);
        check(drain(pipeline).stream().map(RowEnvelope::row).toList().equals(List.of(row(1), row(2), row(1))), "ORDER BY can use a qualified column omitted from projection");
        pipeline.close();

        sqlike.contract.IQueryOptimizer optimizer = (sqlike.contract.IQueryOptimizer) java.lang.reflect.Proxy.newProxyInstance(
                QP4TestDriver.class.getClassLoader(), new Class<?>[]{sqlike.contract.IQueryOptimizer.class}, (proxy, method, args) -> {throw new UnsupportedOperationException(method.getName());});
        sqlike.contract.IConcurrencyControlManager concurrency = (sqlike.contract.IConcurrencyControlManager) java.lang.reflect.Proxy.newProxyInstance(
                QP4TestDriver.class.getClassLoader(), new Class<?>[]{sqlike.contract.IConcurrencyControlManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "initAfterRecovery" -> null;
                    case "requestRead" -> new sqlike.shared.AccessResponse(sqlike.shared.AccessStatus.GRANTED, Optional.empty(), Optional.empty(), Optional.empty());
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        sqlike.contract.IFailureRecoveryManager recovery = (sqlike.contract.IFailureRecoveryManager) java.lang.reflect.Proxy.newProxyInstance(
                QP4TestDriver.class.getClassLoader(), new Class<?>[]{sqlike.contract.IFailureRecoveryManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("recover")) {return new sqlike.shared.RecoveryResult(0, 0, 0, Optional.empty());}
                    throw new UnsupportedOperationException(method.getName());
                });
        QueryProcessor processor = new QueryProcessor(optimizer, storage, concurrency, recovery, tiny);
        processor.initialize();
        var result = processor.executePlan(new QueryPlan(joined, 0), 47);
        check(result.kind() == ResultKind.ERROR && result.error().orElseThrow().code() == sqlike.shared.ErrorCode.INVALID_VALUE,
                "facade propagates configured budget to executing join");
    }

    private static void rightSubtree() {
        Probe b = new Probe(schema("B", "id"), List.of(envelope(2), envelope(3)));
        Probe c = new Probe(schema("C", "id"), List.of(envelope(2), envelope(4)));
        BlockNestedLoopJoinOperator natural = join(b, c, schema(null, "id"), List.of(0), spec(JoinType.NATURAL, List.of("id")));
        natural.setMaxOuterBytes(24);
        FilterOperator filter = new FilterOperator(natural, new FilterSpec(relation(ExpressionOperator.GT, column("C", "id"), literal(0))), EVALUATOR, natural.schema());
        SortOperator sorted = new SortOperator(filter, new SortSpec(ColumnRef.of("B", "id"), SortDirection.ASC), new RowComparator(filter.schema(), ColumnRef.of("B", "id"), SortDirection.ASC, VALUES), filter.schema());
        ProjectOperator project = new ProjectOperator(sorted, new ProjectSpec(List.of(ColumnRef.of("C", "id"))), natural.schema());
        Probe a = new Probe(schema("A", "id"), List.of(envelope(2), envelope(2), envelope(3)));
        JoinSpec theta = new JoinSpec(JoinType.THETA, Optional.of(relation(ExpressionOperator.EQ, column("A", "id"), column("B", "id"))), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
        BlockNestedLoopJoinOperator root = join(a, project, schema(null, "id", "id"), List.of(0, 1), theta);
        root.setMaxOuterBytes(24);
        for (int scan = 0; scan < 2; scan++) {
            root.open(CONTEXT);
            checkBag(drain(root).stream().map(RowEnvelope::row).toList(), List.of(row(2, 2), row(2, 2)), "right-deep join rescans filtered/sorted/projected subtree and preserves duplicates");
            root.close();
        }
        check(a.lastContext == CONTEXT && b.lastContext == CONTEXT && c.lastContext == CONTEXT, "all subtree rescans keep the same transaction context");

        BlockNestedLoopJoinOperator noCommon = join(new Probe(schema("D", "x"), List.of(envelope(1))), new Probe(schema("E", "y"), List.of(envelope(2), envelope(3))), schema(null, "x", "y"), List.of(0, 1), spec(JoinType.NATURAL, List.of()));
        noCommon.open(CONTEXT);
        checkBag(drain(noCommon).stream().map(RowEnvelope::row).toList(), List.of(row(1, 2), row(1, 3)), "NATURAL with no common columns is Cartesian");
        noCommon.close();
        BlockNestedLoopJoinOperator multiKey = join(new Probe(schema("F", "id", "key"), List.of(RowEnvelope.of(row(1, 2)), RowEnvelope.of(row(1, 3)))), new Probe(schema("G", "id", "key"), List.of(RowEnvelope.of(row(1, 3)), RowEnvelope.of(row(1, 3)))), schema(null, "id", "key"), List.of(0, 1), spec(JoinType.NATURAL, List.of("id", "key")));
        multiKey.open(CONTEXT);
        checkBag(drain(multiKey).stream().map(RowEnvelope::row).toList(), List.of(row(1, 3), row(1, 3)), "NATURAL checks all common keys and preserves duplicate matches");
        multiKey.close();

        BlockNestedLoopJoinOperator bushy = join(noCommon, multiKey, schema(null, "x", "y", "id", "key"), List.of(0, 1, 2, 3), spec(JoinType.CARTESIAN, List.of()));
        bushy.setMaxOuterBytes(32);
        bushy.open(CONTEXT);
        checkBag(drain(bushy).stream().map(RowEnvelope::row).toList(), List.of(row(1, 2, 1, 3), row(1, 2, 1, 3), row(1, 3, 1, 3), row(1, 3, 1, 3)), "four-table bushy plan rescans its join child");
        check(bushy.schema().findColumnOffset("G", "key") == 3, "bushy output preserves natural-join qualifiers");
        bushy.close();

        for (JoinType type : JoinType.values()) {
            for (boolean emptyLeft : List.of(true, false)) {
                Probe emptyA = new Probe(schema("A", "id"), emptyLeft ? List.of() : List.of(envelope(1)));
                Probe emptyB = new Probe(schema("B", "id"), emptyLeft ? List.of(envelope(1)) : List.of());
                JoinSpec emptySpec = type == JoinType.THETA ? theta : spec(type, type == JoinType.NATURAL ? List.of("id") : List.of());
                boolean naturalOutput = type == JoinType.NATURAL;
                BlockNestedLoopJoinOperator empty = join(emptyA, emptyB, naturalOutput ? schema(null, "id") : schema(null, "id", "id"), naturalOutput ? List.of(0) : List.of(0, 1), emptySpec);
                empty.open(CONTEXT);
                check(drain(empty).isEmpty(), "empty child yields no rows for " + type);
                empty.close();
            }
        }
    }

    private static void duplicateInputs() {
        for (JoinType type : JoinType.values()) {
            List<Row> expected = type == JoinType.CARTESIAN
                    ? List.of(row(1, 1), row(1, 1), row(1, 3), row(1, 1), row(1, 1), row(1, 3), row(2, 1), row(2, 1), row(2, 3))
                    : java.util.Collections.nCopies(4, type == JoinType.NATURAL ? row(1) : row(1, 1));
            for (long budget : List.of(24L, 48L)) {
                Probe left = new Probe(schema("A", "id"), List.of(envelope(1), envelope(1), envelope(2)));
                Probe right = new Probe(schema("B", "id"), List.of(envelope(1), envelope(1), envelope(3)));
                JoinSpec spec = type == JoinType.THETA
                        ? new JoinSpec(type, Optional.of(relation(ExpressionOperator.EQ, column("A", "id"), column("B", "id"))), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of())
                        : spec(type, type == JoinType.NATURAL ? List.of("id") : List.of());
                boolean natural = type == JoinType.NATURAL;
                BlockNestedLoopJoinOperator join = join(left, right, natural ? schema(null, "id") : schema(null, "id", "id"), natural ? List.of(0) : List.of(0, 1), spec);
                join.setMaxOuterBytes(budget);
                join.open(CONTEXT);
                checkBag(drain(join).stream().map(RowEnvelope::row).toList(), expected, type + " preserves duplicates on both inputs with budget " + budget);
                join.close();
            }
        }
        check(bag(List.of(row(1), row(1), row(2))).equals(bag(List.of(row(2), row(1), row(1)))), "unordered results allow a different row order");
        check(!bag(List.of(row(1), row(1), row(2))).equals(bag(List.of(row(1), row(2)))), "unordered results still detect missing duplicates");
    }

    private static void rescanFailures() {
        for (boolean adapted : List.of(false, true)) {
            for (boolean failReading : List.of(false, true)) {
                int[] lifecycle = new int[4];
                IStorageManager delegate = readOnlyStorage();
                IStorageManager storage = (IStorageManager) java.lang.reflect.Proxy.newProxyInstance(QP4TestDriver.class.getClassLoader(), new Class<?>[]{IStorageManager.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("scan")) {return method.invoke(delegate, args);}
                    boolean inner = args[0].equals("B");
                    int scan = ++lifecycle[inner ? 1 : 0];
                    boolean failingScan = inner && scan == 2;
                    if (failingScan && !failReading) {
                        throw new QpException(sqlike.shared.ErrorCode.IO_ERROR, "fixture rescan open failure");
                    }
                    RecordCursor cursor = (RecordCursor) method.invoke(delegate, args);
                    return new RecordCursor() {
                        private boolean closed;
                        @Override public boolean hasNext() {return cursor.hasNext();}
                        @Override public sqlike.shared.Record next() {
                            if (failingScan) {throw new QpException(sqlike.shared.ErrorCode.IO_ERROR, "fixture rescan read failure");}
                            return cursor.next();
                        }
                        @Override public void close() {
                            if (!closed) {
                                closed = true;
                                lifecycle[inner ? 3 : 2]++;
                                cursor.close();
                            }
                        }
                    };
                });
                QpConfiguration defaults = QpConfiguration.defaults();
                QpConfiguration config = new QpConfiguration(0, 2, 40, defaults.defaultIndexKind(), defaults.temporaryDirectory());
                PlanTranslator translator = adapted
                        ? PlanTranslator.withQpStorage(new StorageAdapter(storage), null, VALUES, null, null, null, null, config)
                        : new PlanTranslator(storage, null, VALUES, null, null, null, null, config);
                Operator root = translator.translateSelect(PlanNodes.join(PlanNodes.scan("A"), PlanNodes.scan("B"), spec(JoinType.CARTESIAN, List.of())));
                ExecutionContext context = ExecutionContext.of(50);
                for (String table : List.of("A", "B")) {
                    context.addGrant(new sqlike.qp.port.QpContracts.AccessGrant(
                            new sqlike.qp.port.QpContracts.TxId(context.txId()),
                            sqlike.qp.port.QpContracts.ResourceKey.table(new sqlike.qp.port.QpContracts.TableId(table)),
                            sqlike.qp.port.QpContracts.AccessMode.READ));
                }
                try {
                    new ExecutionEngine().executeSelect(root, context, null);
                    throw new AssertionError("Rescan cursor failure became successful EOF");
                } catch (QpException error) {
                    check(error.getErrorInfo().code() == sqlike.shared.ErrorCode.IO_ERROR && error.getMessage().contains(failReading ? "rescan read failure" : "rescan open failure"), "rescan failure retains its I/O error (adapted=" + adapted + ", reading=" + failReading + "): " + error.getErrorInfo());
                }
                check(lifecycle[0] == 1 && lifecycle[1] == 2, "failure occurs on the second inner scan");
                check(lifecycle[2] == 1 && lifecycle[3] == (failReading ? 2 : 1), "engine closes every opened cursor on rescan failure");
                root.close();
                check(lifecycle[2] == 1 && lifecycle[3] == (failReading ? 2 : 1), "cleanup after failure is idempotent");
                root.open(context);
                checkBag(drain(root).stream().map(RowEnvelope::row).toList(), List.of(row(1, 2), row(1, 3), row(2, 2), row(2, 3)), "closed join can execute again after rescan failure");
                root.close();
            }
        }
    }

    private static Map<Row, Long> bag(List<Row> rows) {
        Map<Row, Long> counts = new HashMap<>();
        for (Row row : rows) {counts.merge(row, 1L, Long::sum);}
        return counts;
    }

    private static void checkBag(List<Row> actual, List<Row> expected, String message) {
        check(bag(actual).equals(bag(expected)), message);
    }

    private static IStorageManager readOnlyStorage() {
        return (IStorageManager) java.lang.reflect.Proxy.newProxyInstance(QP4TestDriver.class.getClassLoader(), new Class<?>[]{IStorageManager.class}, (proxy, method, args) -> {
            String table = (String) args[0];
            return switch (method.getName()) {
                case "getSchema" -> new TableSchema(table, table, schema(table, "id").columns());
                case "scan" -> new RecordCursor() {
                    private final java.util.Iterator<Integer> values = (table.equals("A") ? List.of(1, 2) : List.of(2, 3)).iterator();
                    private boolean closed;
                    private int slot;
                    @Override public boolean hasNext() {return !closed && values.hasNext();}
                    @Override public sqlike.shared.Record next() {
                        if (!hasNext()) {throw new java.util.NoSuchElementException();}
                        return new sqlike.shared.Record(new RID(new PageId(table, 0), slot++), List.of(integer(values.next())));
                    }
                    @Override public void close() {closed = true;}
                };
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }

    private static Expression relation(ExpressionOperator operator, Expression left, Expression right) {
        return new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(), Optional.of(operator), List.of(left, right));
    }

    private static BlockNestedLoopJoinOperator join(Operator left, Operator right, Schema schema, List<Integer> mapping, JoinSpec spec) {
        return new BlockNestedLoopJoinOperator(left, right, spec, new JoinLayout(left.schema(), right.schema(), schema, mapping),
                EVALUATOR, VALUES, new RowSizeEstimator(), schema);
    }

    private static SortOperator sort(Operator child, SortDirection direction) {
        ColumnRef column = ColumnRef.of("T", "key");
        return new SortOperator(child, new SortSpec(column, direction), new RowComparator(child.schema(), column, direction, VALUES), child.schema());
    }

    private static JoinSpec spec(JoinType type, List<String> common) {
        return new JoinSpec(type, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, common);
    }

    private static Schema schema(String table, String... columns) {
        return Schema.of(table, java.util.Arrays.stream(columns).map(name -> new ColumnSchema(name, DataType.INTEGER, Optional.empty())).toList());
    }

    private static Value integer(int value) {return new Value(DataType.INTEGER, Optional.of(value), Optional.empty(), Optional.empty());}

    private static Row row(int... values) {return Row.of(java.util.Arrays.stream(values).mapToObj(QP4TestDriver::integer).toList());}

    private static RowEnvelope envelope(int value) {return RowEnvelope.of(row(value));}

    private static Expression column(String table, String column) {
        return new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(ColumnRef.of(table, column)), Optional.empty(), List.of());
    }

    private static Expression literal(int value) {
        return new Expression(ExpressionKind.LITERAL, Optional.of(integer(value)), Optional.empty(), Optional.empty(), List.of());
    }

    private static List<RowEnvelope> drain(Operator operator) {
        List<RowEnvelope> result = new ArrayList<>();
        Optional<RowEnvelope> row;
        while ((row = operator.next()).isPresent()) {
            result.add(row.get());
        }
        check(operator.next().isEmpty(), "END is repeatable");
        return result;
    }

    private static void run(String name, Runnable scenario) {
        try {
            scenario.run();
            scenarios++;
            System.out.println("PASS " + name);
        } catch (RuntimeException | Error error) {
            System.err.println("FAIL " + name + ": " + error.getMessage());
            throw error;
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            if (type.isInstance(error)) {checks++; return;}
            throw error;
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {throw new AssertionError(message);}
        checks++;
    }

    private static final class Probe extends AbstractOperator {
        private final List<RowEnvelope> data;
        private int cursor;
        private int opens;
        private int closes;
        private int nextCalls;
        private boolean failOpen;
        private boolean failClose;
        private ExecutionContext lastContext;
        private Runnable onNext;

        private Probe(Schema schema) {
            this(schema, List.of());
        }

        private Probe(Schema schema, List<RowEnvelope> data) {
            super(schema);
            this.data = List.copyOf(data);
        }

        @Override
        protected void doOpen() {
            opens++;
            cursor = 0;
            lastContext = requireContext();
            if (failOpen) {throw new IllegalStateException("fixture open failure");}
        }

        @Override
        protected Optional<RowEnvelope> doNext() {
            nextCalls++;
            if (onNext != null) {onNext.run();}
            return cursor < data.size() ? Optional.of(data.get(cursor++)) : Optional.empty();
        }

        @Override
        protected void doClose() {
            closes++;
            cursor = 0;
            if (failClose) {throw new IllegalStateException("fixture close failure");}
        }
    }
}
