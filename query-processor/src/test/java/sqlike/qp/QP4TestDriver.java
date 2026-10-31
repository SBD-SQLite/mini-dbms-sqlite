package sqlike.qp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

/** M1 fixture/lifecycle checks; expected rows are scripted, not computed by BNLJ or sort. */
public class QP4TestDriver {
    private static final IValueComparator VALUES = new ValueComparator();
    private static final IExpressionEvaluator EVALUATOR = new ExpressionEvaluator(VALUES);
    private static final ExecutionContext CONTEXT = ExecutionContext.of(41, "qp4-m1");
    private static int scenarios;
    private static int checks;

    public static void main(String[] args) {
        run("layout concatenation, output mapping and validation", QP4TestDriver::layout);
        run("comparator direction, numeric promotion and ties", QP4TestDriver::comparator);
        run("logical estimator and UTF-8 byte sizes", QP4TestDriver::estimator);
        run("explicit Cartesian/theta/natural fixtures and rescan", QP4TestDriver::joins);
        run("empty fixtures, invalid state and fixture ownership", QP4TestDriver::lifecycle);
        run("ASC/DESC/default sort fixtures and RID preservation", QP4TestDriver::sorts);
        run("QP3 filter/project composition and three-table lifecycle", QP4TestDriver::composition);
        run("partial open and cleanup errors", QP4TestDriver::failures);
        System.out.println("QP4 M1: " + scenarios + "/8 scenarios, " + checks + " checks PASS (fixtures only)");
    }

    private static void layout() {
        Schema left = schema("A", "id", "amount");
        Schema right = schema("B", "id", "value");
        Schema output = schema(null, "id", "value");
        List<Integer> mapping = new ArrayList<>(List.of(0, 3));
        JoinLayout layout = new JoinLayout(left, right, output, mapping);
        mapping.set(0, 1);
        check(layout.getInputSchema().columnCount() == 4, "predicate schema concatenates both children");
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
            check(drain(join).equals(expected), "mock preserves registered rows and duplicates");
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
        expect(QpException.class, () -> join.open(CONTEXT));
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
            expect(QpException.class, () -> sort.open(CONTEXT));
            sort.close();
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
            check(child.opens == 2 && child.closes == 2 && child.nextCalls == 0, "sort uses fixture, not child sort algorithm");
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
        check(drain(project).stream().map(RowEnvelope::row).toList().equals(List.of(row(8, 2))), "real QP3 filter/project consumes join fixture");
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
        check(a.nextCalls == 0 && b.nextCalls == 0 && c.nextCalls == 0, "composition does not execute production join");
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
        private int opens;
        private int closes;
        private int nextCalls;
        private boolean failOpen;
        private boolean failClose;
        private ExecutionContext lastContext;

        private Probe(Schema schema) {super(schema);}

        @Override
        protected void doOpen() {
            opens++;
            lastContext = requireContext();
            if (failOpen) {throw new IllegalStateException("fixture open failure");}
        }

        @Override
        protected Optional<RowEnvelope> doNext() {
            nextCalls++;
            return Optional.empty();
        }

        @Override
        protected void doClose() {
            closes++;
            if (failClose) {throw new IllegalStateException("fixture close failure");}
        }
    }
}