package sqlike.qo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import sqlike.contract.IStorageManager;
import sqlike.shared.*;

public final class Binder {
    private final IStorageManager storage;

    public Binder(IStorageManager storage) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        this.storage = storage;
    }

    public PlanNode bind(ParsedQuery query) {
        if (query == null) throw QoErrors.invalidValue("ParsedQuery is null", 0);
        PlanNode root = query.root().orElseThrow(() -> QoErrors.invalidState("statement has no plan root"));
        PlanNodes.validate(root);
        return bindNode(root);
    }

    private PlanNode bindNode(PlanNode node) {
        return switch (node.kind()) {
            case SCAN, INDEX_SCAN -> bindScan(node);
            case FILTER -> bindFilter(node);
            case PROJECT -> bindProject(node);
            case JOIN, CARTESIAN -> bindJoin(node);
            case SORT -> bindSort(node);
            case INSERT, UPDATE, DELETE -> bindMutation(node);
            case CREATE_INDEX -> bindCreateIndex(node);
        };
    }

    private PlanNode bindScan(PlanNode node) {
        ScanSpec scan = node.scan().orElseThrow();
        TableSchema schema = table(scan.tableName());
        if (node.kind() == PlanNodeKind.INDEX_SCAN) {
            try {
                boolean found = storage.getIndexes(schema.tableName()).stream()
                        .anyMatch(i -> i.indexName().equalsIgnoreCase(scan.indexName().orElseThrow()));
                if (!found) throw QoErrors.semantic("unknown index '" + scan.indexName().orElseThrow() + "'");
            } catch (sqlike.storage.StorageException e) {
                throw QoErrors.semantic("storage error: " + e.getMessage());
            }
        }
        List<ResultColumn> outputs = schema.columns().stream()
                .map(c -> new ResultColumn(c.name(), Optional.of(ColumnRef.of(schema.tableName(), c.name())), c.dataType()))
                .toList();
        return copy(node, List.of(), outputs, Optional.of(scan), node.predicate(), node.projection(), node.join(), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindFilter(PlanNode node) {
        PlanNode child = bindNode(node.children().get(0));
        Expression predicate = bindExpression(node.predicate().orElseThrow(), scope(child));
        if (!isBoolean(predicate))
            throw QoErrors.semantic("WHERE condition must be boolean; found " + expressionContext(predicate));
        return copy(node, List.of(child), child.outputColumns(), node.scan(), Optional.of(predicate), node.projection(), node.join(), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindProject(PlanNode node) {
        PlanNode child = bindNode(node.children().get(0));
        List<ColumnRef> bound = new ArrayList<>();
        List<ResultColumn> outputs = new ArrayList<>();
        for (ColumnRef ref : node.projection()) {
            if (ref.columnName().equals("*")) {
                int expandedBefore = outputs.size();
                if (ref.tableName().isPresent() && scope(child).stream().noneMatch(t -> same(t.tableName(), ref.tableName().get())))
                    throw QoErrors.semantic("unknown table '" + ref.tableName().get() + "' in wildcard");
                for (ResultColumn output : child.outputColumns()) {
                    if (ref.tableName().isPresent() && (output.source().isEmpty() ||
                            !same(ref.tableName().get(), output.source().get().tableName().orElse("")))) continue;
                    ColumnRef source = output.source().orElseThrow(() -> QoErrors.semantic("cannot expand wildcard over an unbound output"));
                    bound.add(source);
                    outputs.add(output);
                }
                if (ref.tableName().isPresent() && outputs.size() == expandedBefore)
                    throw QoErrors.semantic("table '" + ref.tableName().get() + "' has no visible columns");
            } else {
                ColumnRef resolved = resolve(ref, scope(child));
                ColumnSchema column = columnSchema(resolved);
                bound.add(resolved);
                outputs.add(new ResultColumn(resolved.columnName(), Optional.of(resolved), column.dataType()));
            }
        }
        if (outputs.isEmpty()) throw QoErrors.semantic("SELECT list contains no columns");
        return copy(node, List.of(child), outputs, node.scan(), node.predicate(), bound, node.join(), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindJoin(PlanNode node) {
        PlanNode left = bindNode(node.children().get(0));
        PlanNode right = bindNode(node.children().get(1));
        JoinSpec spec = node.join().orElseThrow();
        List<String> common = spec.commonColumns();
        Optional<Expression> condition = spec.condition();
        if (spec.joinType() == JoinType.NATURAL) {
            Map<String, ColumnSchema> leftNames = uniqueOutputNames(left.outputColumns());
            Map<String, ColumnSchema> rightNames = uniqueOutputNames(right.outputColumns());
            common = leftNames.keySet().stream().filter(rightNames::containsKey).toList();
            if (common.isEmpty()) throw QoErrors.semantic("NATURAL JOIN has no columns in common");
            for (String name : common) {
                if (!compatible(leftNames.get(key(name)).dataType(), rightNames.get(key(name)).dataType()))
                    throw QoErrors.semantic("incompatible types for NATURAL JOIN column '" + name + "'");
            }
        } else if (condition.isPresent()) {
            condition = Optional.of(bindExpression(condition.get(), mergeScope(scope(left), scope(right))));
            validateComparisonTree(condition.get());
        }
        List<ResultColumn> outputs = new ArrayList<>(left.outputColumns());
        for (ResultColumn col : right.outputColumns()) {
            boolean duplicateNatural = spec.joinType() == JoinType.NATURAL && common.stream().anyMatch(n -> same(n, col.name()));
            if (!duplicateNatural) outputs.add(col);
        }
        JoinSpec boundSpec = new JoinSpec(spec.joinType(), condition, spec.algorithm(), common);
        return copy(node, List.of(left, right), outputs, node.scan(), node.predicate(), node.projection(), Optional.of(boundSpec), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindSort(PlanNode node) {
        PlanNode child = bindNode(node.children().get(0));
        SortSpec sort = node.sort().orElseThrow();
        ColumnRef resolved = resolve(sort.column(), scope(child));
        return copy(node, List.of(child), child.outputColumns(), node.scan(), node.predicate(), node.projection(), node.join(),
                Optional.of(new SortSpec(resolved, sort.direction())), node.mutation(), node.createIndex());
    }

    private PlanNode bindMutation(PlanNode node) {
        MutationSpec mutation = node.mutation().orElseThrow();
        TableSchema schema = table(mutation.tableName());
        if (node.kind() == PlanNodeKind.INSERT) {
            if (mutation.insertValues().size() != schema.columns().size())
                throw QoErrors.semantic("INSERT value count does not match table '" + schema.tableName() + "'");
            for (int i = 0; i < schema.columns().size(); i++)
                if (!compatible(schema.columns().get(i).dataType(), mutation.insertValues().get(i).type()))
                    throw QoErrors.semantic("INSERT value type does not match column '" + schema.tableName() + "." + schema.columns().get(i).name() + "'");
            return copy(node, List.of(), List.of(), node.scan(), node.predicate(), node.projection(), node.join(), node.sort(), Optional.of(mutation), node.createIndex());
        }
        PlanNode child = bindNode(node.children().get(0));
        if (node.kind() == PlanNodeKind.UPDATE) {
            ColumnRef target = resolve(mutation.updateColumn().orElseThrow(), scope(child));
            Expression value = bindExpression(mutation.updateExpression().orElseThrow(), scope(child));
            if (!compatible(columnSchema(target).dataType(), expressionType(value)))
                throw QoErrors.semantic("UPDATE expression type does not match column '" + render(target) + "'");
            mutation = new MutationSpec(schema.tableName(), mutation.insertValues(), Optional.of(target), Optional.of(value));
        }
        return copy(node, List.of(child), child.outputColumns(), node.scan(), node.predicate(), node.projection(), node.join(), node.sort(), Optional.of(mutation), node.createIndex());
    }

    private PlanNode bindCreateIndex(PlanNode node) {
        IndexMetadata index = node.createIndex().orElseThrow();
        TableSchema schema = table(index.tableName());
        column(schema, index.columnName());
        return node;
    }

    private Expression bindExpression(Expression expression, List<TableSchema> tables) {
        if (expression.kind() == ExpressionKind.COLUMN) {
            ColumnRef resolved = resolve(expression.column().orElseThrow(), tables);
            return new Expression(expression.kind(), expression.literal(), Optional.of(resolved), expression.operator(), expression.operands());
        }
        List<Expression> operands = expression.operands().stream().map(e -> bindExpression(e, tables)).toList();
        Expression bound = new Expression(expression.kind(), expression.literal(), expression.column(), expression.operator(), operands);
        expressionType(bound);
        return bound;
    }

    private DataType expressionType(Expression expression) {
        if (expression.kind() == ExpressionKind.LITERAL) return expression.literal().orElseThrow().type();
        if (expression.kind() == ExpressionKind.COLUMN) return columnSchema(expression.column().orElseThrow()).dataType();
        ExpressionOperator op = expression.operator().orElseThrow();
        if (op == ExpressionOperator.AND) {
            for (Expression child : expression.operands()) if (!isBoolean(child))
                throw QoErrors.semantic("AND operands must be boolean; found " + expressionContext(child));
            return DataType.INTEGER;
        }
        if (op == ExpressionOperator.NEGATE) {
            DataType type = expressionType(expression.operands().get(0));
            if (!numeric(type)) throw QoErrors.semantic("unary minus requires a numeric operand; found " + expressionContext(expression));
            return type;
        }
        DataType left = expressionType(expression.operands().get(0));
        DataType right = expressionType(expression.operands().get(1));
        if (op == ExpressionOperator.ADD || op == ExpressionOperator.SUBTRACT || op == ExpressionOperator.MULTIPLY || op == ExpressionOperator.DIVIDE) {
            if (!numeric(left) || !numeric(right))
                throw QoErrors.semantic("arithmetic requires numeric operands; found " + expressionContext(expression));
            return left == DataType.FLOAT || right == DataType.FLOAT ? DataType.FLOAT : DataType.INTEGER;
        }
        if (!compatible(left, right))
            throw QoErrors.semantic("comparison operands have incompatible types; found " + expressionContext(expression));
        return DataType.INTEGER;
    }

    private boolean isBoolean(Expression expression) {
        if (expression.kind() == ExpressionKind.BINARY) {
            ExpressionOperator op = expression.operator().orElse(null);
            if (op == ExpressionOperator.AND || isComparison(op)) return true;
        }
        return false;
    }

    private void validateComparisonTree(Expression expression) {
        if (expression.kind() == ExpressionKind.BINARY && isComparison(expression.operator().orElse(null))) {
            DataType left = expressionType(expression.operands().get(0));
            DataType right = expressionType(expression.operands().get(1));
            if (!compatible(left, right))
                throw QoErrors.semantic("comparison operands have incompatible types; found " + expressionContext(expression));
        }
        expression.operands().forEach(this::validateComparisonTree);
    }

    private ColumnRef resolve(ColumnRef ref, List<TableSchema> tables) {
        List<TableSchema> candidates = tables.stream()
                .filter(t -> ref.tableName().isEmpty() || same(t.tableName(), ref.tableName().get()))
                .filter(t -> t.columns().stream().anyMatch(c -> same(c.name(), ref.columnName())))
                .toList();
        if (candidates.isEmpty()) throw QoErrors.semantic("unknown column '" + render(ref) + "'");
        if (candidates.size() > 1) throw QoErrors.semantic("ambiguous column '" + ref.columnName() + "'");
        ColumnSchema col = column(candidates.get(0), ref.columnName());
        return ColumnRef.of(candidates.get(0).tableName(), col.name());
    }

    private List<TableSchema> scope(PlanNode node) {
        Map<String, TableSchema> found = new LinkedHashMap<>();
        collectSchemas(node, found);
        return List.copyOf(found.values());
    }

    private void collectSchemas(PlanNode node, Map<String, TableSchema> found) {
        node.scan().ifPresent(s -> {
            TableSchema schema = table(s.tableName());
            found.putIfAbsent(schema.tableId(), schema);
        });
        node.children().forEach(child -> collectSchemas(child, found));
    }

    private List<TableSchema> mergeScope(List<TableSchema> left, List<TableSchema> right) {
        Map<String, TableSchema> merged = new LinkedHashMap<>();
        left.forEach(t -> merged.put(t.tableId(), t));
        right.forEach(t -> merged.put(t.tableId(), t));
        return List.copyOf(merged.values());
    }

    private Map<String, ColumnSchema> uniqueOutputNames(List<ResultColumn> columns) {
        Map<String, ColumnSchema> result = new LinkedHashMap<>();
        for (ResultColumn output : columns) {
            ColumnRef source = output.source().orElseThrow(() -> QoErrors.semantic("NATURAL JOIN requires resolved columns"));
            result.putIfAbsent(key(output.name()), columnSchema(source));
        }
        return result;
    }

    private TableSchema table(String name) {
        try {
            String actualName = storage.listTables().stream()
                    .filter(tableName -> same(tableName, name))
                    .findFirst()
                    .orElseThrow(() -> QoErrors.semantic("unknown table '" + name + "'"));
            return storage.getSchema(actualName);
        } catch (sqlike.storage.StorageException e) {
            throw QoErrors.semantic("storage error: " + e.getMessage());
        }
    }

    private ColumnSchema columnSchema(ColumnRef ref) {
        TableSchema schema = table(ref.tableName().orElseThrow(() -> QoErrors.semantic("unqualified column is not bound")));
        return column(schema, ref.columnName());
    }

    private ColumnSchema column(TableSchema schema, String name) {
        return schema.columns().stream().filter(c -> same(c.name(), name)).findFirst()
                .orElseThrow(() -> QoErrors.semantic("unknown column '" + name + "' in table '" + schema.tableName() + "'"));
    }

    private static boolean compatible(DataType a, DataType b) {
        return a == b || numeric(a) && numeric(b);
    }

    private static boolean numeric(DataType type) { return type == DataType.INTEGER || type == DataType.FLOAT; }
    private static boolean isComparison(ExpressionOperator op) {
        return op == ExpressionOperator.EQ || op == ExpressionOperator.NE || op == ExpressionOperator.GT ||
                op == ExpressionOperator.GE || op == ExpressionOperator.LT || op == ExpressionOperator.LE;
    }
    private static String render(ColumnRef ref) { return ref.tableName().map(t -> t + ".").orElse("") + ref.columnName(); }

    private String expressionContext(Expression expression) {
        List<String> columns = new ArrayList<>();
        collectColumnNames(expression, columns);
        return columns.isEmpty() ? "expression " + expression : "column(s) " + String.join(", ", columns);
    }

    private void collectColumnNames(Expression expression, List<String> names) {
        expression.column().ifPresent(column -> {
            String name = render(column);
            if (!names.contains(name)) names.add(name);
        });
        expression.operands().forEach(operand -> collectColumnNames(operand, names));
    }

    private static String key(String value) { return value.toLowerCase(Locale.ROOT); }
    private static boolean same(String a, String b) { return a.equalsIgnoreCase(b); }

    private PlanNode copy(PlanNode n, List<PlanNode> children, List<ResultColumn> outputs, Optional<ScanSpec> scan,
                          Optional<Expression> predicate, List<ColumnRef> projection, Optional<JoinSpec> join,
                          Optional<SortSpec> sort, Optional<MutationSpec> mutation, Optional<IndexMetadata> createIndex) {
        return new PlanNode(n.kind(), children, outputs, n.estimatedRows(), scan, predicate, projection, join, sort, mutation, createIndex);
    }
}
