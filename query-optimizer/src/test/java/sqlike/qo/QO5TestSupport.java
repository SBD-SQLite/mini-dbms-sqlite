package sqlike.qo;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

/** Test-only relational evaluator: ordered schemas/rows retain repeated columns and duplicate tuples. */
final class QO5TestSupport {
    private QO5TestSupport() { }

    record Rows(List<ResultColumn> schema, List<List<Object>> values) { }

    static IStorageManager catalog() {
        IStorageManager base = MockStorageManager.create();
        return (IStorageManager) Proxy.newProxyInstance(QO5TestSupport.class.getClassLoader(),
                new Class<?>[] { IStorageManager.class }, (proxy, method, args) -> {
                    if (method.getName().equals("listTables")) return List.of("students", "dept", "takes", "courses");
                    if (args != null && args.length > 0 && "courses".equalsIgnoreCase(String.valueOf(args[0]))) {
                        return switch (method.getName()) {
                            case "getSchema" -> new TableSchema("t_courses", "courses", List.of(
                                    new ColumnSchema("course_id", DataType.INTEGER, Optional.empty()),
                                    new ColumnSchema("title", DataType.VARCHAR, Optional.of(30))));
                            case "getStatistics" -> new TableStatistics(200, 10, 30, 20,
                                    Map.of("course_id", 200L, "title", 200L));
                            case "getIndexes" -> List.<IndexMetadata>of();
                            default -> throw new AssertionError("QO5 must not perform record I/O: " + method.getName());
                        };
                    }
                    return switch (method.getName()) {
                        case "getSchema" -> base.getSchema((String) args[0]);
                        case "getStatistics" -> base.getStatistics((String) args[0]);
                        case "getIndexes" -> base.getIndexes((String) args[0]);
                        default -> throw new AssertionError("QO5 must not perform record I/O: " + method.getName());
                    };
                });
    }

    static Map<String, List<List<Object>>> data() {
        Map<String, List<List<Object>>> data = new LinkedHashMap<>();
        data.put("students", List.of(List.of(1, "Ada", 3.8, 10), List.of(2, "Bima", 2.9, 20),
                List.of(3, "Cici", 3.4, 10), List.of(3, "Cici", 3.4, 10)));
        data.put("dept", List.of(List.of(10, "Math"), List.of(20, "CS"), List.of(30, "Arts")));
        data.put("takes", List.of(List.of(1, 100, "A"), List.of(3, 100, "B"), List.of(3, 200, "A")));
        data.put("courses", List.of(List.of(100, "DB"), List.of(200, "OS")));
        return data;
    }

    static PlanNode bound(String sql) {
        IStorageManager catalog = catalog();
        return new Binder(catalog).bind(new QueryOptimizer(catalog).parseQuery(sql));
    }

    static PlanNode rewritten(String sql) {
        return new SelectionProjectionRewriter().rewrite(bound(sql));
    }

    static Expression expression(String sql) {
        TokenStream tokens = new Lexer().tokenize(sql);
        Expression expression = new ExpressionParser().parseExpression(tokens);
        tokens.expectEndOfStatement();
        return expression;
    }

    static Rows eval(PlanNode plan, Map<String, List<List<Object>>> data) {
        return eval(plan, data, catalog());
    }

    static Rows eval(PlanNode plan, Map<String, List<List<Object>>> data, IStorageManager storage) {
        if (plan.kind() == PlanNodeKind.SCAN)
            return new Rows(plan.outputColumns(), data.get(plan.scan().orElseThrow().tableName().toLowerCase(Locale.ROOT)));
        if (plan.kind() == PlanNodeKind.INDEX_SCAN) return indexScan(plan, data, storage);
        if (QO5Plans.isJoin(plan)) return join(plan, data, storage);
        Rows input = eval(plan.children().get(0), data, storage);
        List<List<Object>> rows = new ArrayList<>();
        switch (plan.kind()) {
            case FILTER -> {
                for (List<Object> row : input.values())
                    if (Boolean.TRUE.equals(value(plan.predicate().orElseThrow(), input.schema(), row))) rows.add(row);
            }
            case PROJECT -> {
                for (List<Object> row : input.values()) {
                    List<Object> projected = new ArrayList<>();
                    for (ColumnRef ref : plan.projection()) projected.add(row.get(offset(input.schema(), ref)));
                    rows.add(List.copyOf(projected));
                }
            }
            case SORT -> {
                rows.addAll(input.values());
                SortSpec sort = plan.sort().orElseThrow();
                int index = offset(input.schema(), sort.column());
                rows.sort((left, right) -> (sort.direction() == SortDirection.DESC ? -1 : 1)
                        * compare(left.get(index), right.get(index)));
            }
            case LIMIT -> rows.addAll(input.values().subList(0,
                    (int) Math.min(input.values().size(), plan.limit().orElseThrow().count())));
            default -> throw new AssertionError("unsupported test operator " + plan.kind());
        }
        return new Rows(plan.outputColumns(), List.copyOf(rows));
    }

    private static Rows join(PlanNode plan, Map<String, List<List<Object>>> data, IStorageManager storage) {
        Rows left = eval(plan.children().get(0), data, storage), right = eval(plan.children().get(1), data, storage);
        JoinSpec spec = plan.join().orElseThrow();
        List<ResultColumn> input = new ArrayList<>(left.schema());
        input.addAll(right.schema());
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> a : left.values()) for (List<Object> b : right.values()) {
            List<Object> combined = new ArrayList<>(a);
            combined.addAll(b);
            boolean matches = true;
            if (spec.joinType() == JoinType.NATURAL) {
                for (String name : spec.commonColumns()) {
                    ColumnRef unqualified = Refs.column(null, name);
                    matches &= compare(a.get(offset(left.schema(), unqualified)), b.get(offset(right.schema(), unqualified))) == 0;
                }
            }
            if (spec.condition().isPresent()) matches &= Boolean.TRUE.equals(value(spec.condition().get(), input, combined));
            if (matches) {
                List<Object> output = new ArrayList<>();
                for (ResultColumn column : plan.outputColumns()) output.add(combined.get(offset(input, column.source().orElseThrow())));
                rows.add(List.copyOf(output));
            }
        }
        return new Rows(plan.outputColumns(), List.copyOf(rows));
    }

    private static Rows indexScan(PlanNode plan, Map<String, List<List<Object>>> data, IStorageManager storage) {
        ScanSpec scan = plan.scan().orElseThrow();
        String table = storage.listTables().stream().filter(name -> name.equalsIgnoreCase(scan.tableName())).findFirst().orElseThrow();
        IndexMetadata index = storage.getIndexes(table).stream()
                .filter(metadata -> metadata.indexName().equalsIgnoreCase(scan.indexName().orElseThrow())).findFirst().orElseThrow();
        IndexCondition condition = scan.condition().orElseThrow();
        check(index.indexType() == IndexType.BPLUS_TREE || condition.operator() == IndexOperator.EQ, "HASH range lookup");
        int column = -1;
        for (int i = 0; i < plan.outputColumns().size(); i++)
            if (plan.outputColumns().get(i).source().orElseThrow().columnName().equalsIgnoreCase(index.columnName())) column = i;
        check(column >= 0, "index key column missing");
        equal(plan.outputColumns().get(column).dataType(), condition.key().type());
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : data.get(table.toLowerCase(Locale.ROOT))) {
            int lower = compare(row.get(column), scalar(condition.key()));
            boolean matches = switch (condition.operator()) {
                case EQ -> lower == 0;
                case GT -> lower > 0;
                case GE -> lower >= 0;
                case LT -> lower < 0;
                case LE -> lower <= 0;
                case BETWEEN -> {
                    int upper = compare(row.get(column), scalar(condition.upperBound().orElseThrow()));
                    yield (condition.lowerInclusive() ? lower >= 0 : lower > 0)
                            && (condition.upperInclusive() ? upper <= 0 : upper < 0);
                }
            };
            if (matches) rows.add(row);
        }
        return new Rows(plan.outputColumns(), List.copyOf(rows));
    }

    private static Object scalar(Value value) {
        return switch (value.type()) {
            case INTEGER -> value.integerValue().orElseThrow();
            case FLOAT -> value.floatValue().orElseThrow();
            case CHAR, VARCHAR -> value.textValue().orElseThrow();
        };
    }

    private static int offset(List<ResultColumn> schema, ColumnRef column) {
        for (int i = 0; i < schema.size(); i++) {
            ResultColumn candidate = schema.get(i);
            if (column.tableName().isEmpty() && candidate.name().equalsIgnoreCase(column.columnName())) return i;
            if (column.tableName().isPresent() && candidate.source().isPresent()
                    && QO5Plans.key(candidate.source().get()).equals(QO5Plans.key(column))) return i;
        }
        throw new AssertionError("unavailable column " + column + " in " + schema);
    }

    private static Object value(Expression expression, List<ResultColumn> schema, List<Object> row) {
        if (expression.kind() == ExpressionKind.COLUMN) return row.get(offset(schema, expression.column().orElseThrow()));
        if (expression.kind() == ExpressionKind.LITERAL) {
            Value literal = expression.literal().orElseThrow();
            return switch (literal.type()) {
                case INTEGER -> literal.integerValue().orElseThrow();
                case FLOAT -> literal.floatValue().orElseThrow();
                case CHAR, VARCHAR -> literal.textValue().orElseThrow();
            };
        }
        Object left = value(expression.operands().get(0), schema, row);
        if (expression.kind() == ExpressionKind.UNARY) return -((Number) left).doubleValue();
        Object right = value(expression.operands().get(1), schema, row);
        return switch (expression.operator().orElseThrow()) {
            case EQ -> compare(left, right) == 0;
            case NE -> compare(left, right) != 0;
            case GT -> compare(left, right) > 0;
            case GE -> compare(left, right) >= 0;
            case LT -> compare(left, right) < 0;
            case LE -> compare(left, right) <= 0;
            case AND -> (Boolean) left && (Boolean) right;
            case ADD -> ((Number) left).doubleValue() + ((Number) right).doubleValue();
            case SUBTRACT -> ((Number) left).doubleValue() - ((Number) right).doubleValue();
            case MULTIPLY -> ((Number) left).doubleValue() * ((Number) right).doubleValue();
            case DIVIDE -> ((Number) left).doubleValue() / ((Number) right).doubleValue();
            default -> throw new AssertionError("unsupported test expression");
        };
    }

    private static int compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) return Double.compare(a.doubleValue(), b.doubleValue());
        return ((String) left).compareTo((String) right);
    }

    static void equivalent(PlanNode before, PlanNode after, Map<String, List<List<Object>>> data) {
        equivalent(before, after, data, catalog());
    }

    static void equivalent(PlanNode before, PlanNode after, Map<String, List<List<Object>>> data, IStorageManager storage) {
        PlanNodes.validate(after);
        Rows expected = eval(before, data, storage), actual = eval(after, data, storage);
        equal(expected.schema(), actual.schema());
        equal(bag(expected.values()), bag(actual.values()));
        validateReferences(after);
    }

    private static Map<List<Object>, Integer> bag(List<List<Object>> rows) {
        Map<List<Object>, Integer> result = new HashMap<>();
        for (List<Object> row : rows) result.merge(row, 1, Integer::sum);
        return result;
    }

    private static void validateReferences(PlanNode plan) {
        if (!plan.children().isEmpty()) {
            var available = new java.util.HashSet<String>();
            plan.children().forEach(child -> available.addAll(QO5Plans.available(child)));
            plan.predicate().ifPresent(predicate -> check(available.containsAll(QO5Plans.columns(predicate)), "filter scope"));
            plan.join().flatMap(JoinSpec::condition).ifPresent(predicate ->
                    check(available.containsAll(QO5Plans.columns(predicate)), "join scope"));
            plan.projection().forEach(ref -> check(available.contains(QO5Plans.key(ref)), "projection scope"));
        }
        plan.children().forEach(QO5TestSupport::validateReferences);
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }
}
