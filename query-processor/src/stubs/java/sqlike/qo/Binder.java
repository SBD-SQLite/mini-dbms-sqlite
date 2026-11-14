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
        return bindNode(root, List.of());
    }

    private PlanNode bindNode(PlanNode node, List<TableSchema> outerScope) {
        return switch (node.kind()) {
            case SCAN, INDEX_SCAN -> bindScan(node);
            case FILTER -> bindFilter(node, outerScope);
            case PROJECT -> bindProject(node, outerScope);
            case JOIN, CARTESIAN -> bindJoin(node, outerScope);
            case SORT -> bindSort(node, outerScope);
            case LIMIT -> bindLimit(node, outerScope);
            case INSERT, UPDATE, DELETE -> bindMutation(node, outerScope);
            case CREATE_INDEX -> bindCreateIndex(node);
            case CREATE_TABLE -> bindCreateTable(node);
            case DROP_TABLE -> bindDropTable(node);
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
        String qualifier = scan.alias().orElse(schema.tableName());
        List<ResultColumn> outputs = schema.columns().stream()
                .map(c -> new ResultColumn(c.name(), Optional.of(ColumnRef.of(qualifier, c.name())), c.dataType()))
                .toList();
        return copy(node, List.of(), outputs, Optional.of(scan), node.predicate(), node.projection(), node.join(), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindFilter(PlanNode node, List<TableSchema> outerScope) {
        PlanNode child = bindNode(node.children().get(0), outerScope);
        List<TableSchema> localScope = scope(child);
        Expression predicate = bindExpression(node.predicate().orElseThrow(), localScope, outerScope);
        if (!isBoolean(predicate))
            throw QoErrors.semantic("WHERE condition must be boolean; found " + expressionContext(predicate));
        return copy(node, List.of(child), child.outputColumns(), node.scan(), Optional.of(predicate), node.projection(), node.join(), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindProject(PlanNode node, List<TableSchema> outerScope) {
        PlanNode child = bindNode(node.children().get(0), outerScope);
        List<TableSchema> localScope = scope(child);
        List<ColumnRef> bound = new ArrayList<>();
        List<ResultColumn> outputs = new ArrayList<>();
        for (ColumnRef ref : node.projection()) {
            if (ref.columnName().equals("*")) {
                int expandedBefore = outputs.size();
                if (ref.tableName().isPresent() && localScope.stream().noneMatch(t -> same(t.tableName(), ref.tableName().get())))
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
                ColumnRef resolved = resolveInChild(ref, child, localScope, outerScope);
                ColumnSchema column = columnSchema(resolved, mergeScope(localScope, outerScope));
                bound.add(resolved);
                outputs.add(new ResultColumn(resolved.columnName(), Optional.of(resolved), column.dataType()));
            }
        }
        if (outputs.isEmpty()) throw QoErrors.semantic("SELECT list contains no columns");
        return copy(node, List.of(child), outputs, node.scan(), node.predicate(), bound, node.join(), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindJoin(PlanNode node, List<TableSchema> outerScope) {
        PlanNode left = bindNode(node.children().get(0), outerScope);
        PlanNode right = bindNode(node.children().get(1), outerScope);
        List<TableSchema> leftScope = scope(left);
        List<TableSchema> rightScope = scope(right);
        for (TableSchema r : rightScope) {
            if (leftScope.stream().anyMatch(l -> same(l.tableName(), r.tableName()))) {
                throw QoErrors.semantic("duplicate table or alias '" + r.tableName() + "' in FROM");
            }
        }
        List<TableSchema> joinedScope = mergeScope(leftScope, rightScope);
        JoinSpec spec = node.join().orElseThrow();
        List<String> common = spec.commonColumns();
        Optional<Expression> condition = spec.condition();
        if (spec.joinType() == JoinType.NATURAL) {
            Map<String, ColumnSchema> leftNames = uniqueOutputNames(left.outputColumns(), leftScope);
            Map<String, ColumnSchema> rightNames = uniqueOutputNames(right.outputColumns(), rightScope);
            common = leftNames.keySet().stream().filter(rightNames::containsKey).toList();
            if (common.isEmpty()) throw QoErrors.semantic("NATURAL JOIN has no columns in common");
            for (String name : common) {
                if (!compatible(leftNames.get(key(name)).dataType(), rightNames.get(key(name)).dataType()))
                    throw QoErrors.semantic("incompatible types for NATURAL JOIN column '" + name + "'");
            }
        } else if (condition.isPresent()) {
            condition = Optional.of(bindExpression(condition.get(), joinedScope, outerScope));
            validateComparisonTree(condition.get(), mergeScope(joinedScope, outerScope));
        }
        List<ResultColumn> outputs = new ArrayList<>(left.outputColumns());
        for (ResultColumn col : right.outputColumns()) {
            boolean duplicateNatural = spec.joinType() == JoinType.NATURAL && common.stream().anyMatch(n -> same(n, col.name()));
            if (!duplicateNatural) outputs.add(col);
        }
        JoinSpec boundSpec = new JoinSpec(spec.joinType(), condition, spec.algorithm(), common);
        return copy(node, List.of(left, right), outputs, node.scan(), node.predicate(), node.projection(), Optional.of(boundSpec), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindSort(PlanNode node, List<TableSchema> outerScope) {
        PlanNode child = bindNode(node.children().get(0), outerScope);
        SortSpec sort = node.sort().orElseThrow();
        ColumnRef resolved = resolveInChild(sort.column(), child, scope(child), outerScope);
        return copy(node, List.of(child), child.outputColumns(), node.scan(), node.predicate(), node.projection(), node.join(),
                Optional.of(new SortSpec(resolved, sort.direction())), node.mutation(), node.createIndex());
    }

    private PlanNode bindLimit(PlanNode node, List<TableSchema> outerScope) {
        PlanNode child = bindNode(node.children().get(0), outerScope);
        LimitSpec limit = node.limit().orElseThrow();
        if (limit.count() < 0) throw QoErrors.invalidValue("LIMIT must be non-negative", 0);
        return copy(node, List.of(child), child.outputColumns(), node.scan(), node.predicate(), node.projection(), node.join(),
                node.sort(), node.mutation(), node.createIndex());
    }

    private ColumnRef resolveInChild(ColumnRef ref, PlanNode child, List<TableSchema> localScope, List<TableSchema> outerScope) {
        if (ref.tableName().isEmpty() && child.outputColumns() != null && !child.outputColumns().isEmpty()) {
            List<ResultColumn> visible = child.outputColumns().stream()
                    .filter(c -> same(c.name(), ref.columnName()) && c.source().isPresent())
                    .toList();
            if (visible.size() == 1) {
                return visible.get(0).source().get();
            }
        }
        return resolve(ref, localScope, outerScope);
    }

    private PlanNode bindMutation(PlanNode node, List<TableSchema> outerScope) {
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
        PlanNode child = bindNode(node.children().get(0), outerScope);
        List<TableSchema> localScope = scope(child);
        if (node.kind() == PlanNodeKind.UPDATE) {
            ColumnRef target = resolve(mutation.updateColumn().orElseThrow(), localScope, outerScope);
            Expression value = bindExpression(mutation.updateExpression().orElseThrow(), localScope, outerScope);
            if (!compatible(columnSchema(target, mergeScope(localScope, outerScope)).dataType(), expressionType(value, mergeScope(localScope, outerScope))))
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

    private PlanNode bindCreateTable(PlanNode node) {
        CreateTableSpec spec = node.createTable().orElseThrow();
        if (spec.columns().isEmpty()) throw QoErrors.semantic("CREATE TABLE requires at least one column");
        return node;
    }

    private PlanNode bindDropTable(PlanNode node) {
        node.dropTable().orElseThrow();
        return node;
    }

    private Expression bindExpression(Expression expression, List<TableSchema> localTables, List<TableSchema> outerTables) {
        if (expression.kind() == ExpressionKind.COLUMN) {
            ColumnRef resolved = resolve(expression.column().orElseThrow(), localTables, outerTables);
            return new Expression(expression.kind(), expression.literal(), Optional.of(resolved), expression.operator(), expression.operands(), expression.subquery());
        }
        if (expression.kind() == ExpressionKind.SUBQUERY) {
            SubquerySpec sub = expression.subquery().orElseThrow();
            List<TableSchema> combinedOuter = mergeScope(localTables, outerTables);
            PlanNode boundSubPlan = bindNode(sub.root(), combinedOuter);
            if ((sub.type() == SubqueryType.SCALAR || sub.type() == SubqueryType.IN) && boundSubPlan.outputColumns().size() != 1) {
                throw QoErrors.semantic(sub.type() + " subquery must project exactly 1 column; found " + boundSubPlan.outputColumns().size());
            }
            List<Expression> boundOperands = new ArrayList<>();
            if (sub.type() == SubqueryType.IN) {
                if (expression.operands().size() != 1) {
                    throw QoErrors.semantic("IN subquery requires a left-hand expression");
                }
                Expression cmp = bindExpression(expression.operands().get(0), localTables, outerTables);
                DataType leftType = expressionType(cmp, combinedOuter);
                DataType subType = boundSubPlan.outputColumns().get(0).dataType();
                if (!compatible(leftType, subType)) {
                    throw QoErrors.semantic("IN expression type " + leftType + " is incompatible with subquery column type " + subType);
                }
                boundOperands.add(cmp);
            }
            boolean correlated = isCorrelated(boundSubPlan, scope(boundSubPlan), combinedOuter);
            SubquerySpec boundSub = new SubquerySpec(sub.type(), boundSubPlan, correlated);
            return new Expression(ExpressionKind.SUBQUERY, Optional.empty(), Optional.empty(), Optional.empty(), boundOperands, Optional.of(boundSub));
        }
        List<Expression> operands = expression.operands().stream().map(e -> bindExpression(e, localTables, outerTables)).toList();
        Expression bound = new Expression(expression.kind(), expression.literal(), expression.column(), expression.operator(), operands, expression.subquery());
        expressionType(bound, mergeScope(localTables, outerTables));
        return bound;
    }

    private DataType expressionType(Expression expression, List<TableSchema> tables) {
        if (expression.kind() == ExpressionKind.LITERAL) return expression.literal().orElseThrow().type();
        if (expression.kind() == ExpressionKind.COLUMN) return columnSchema(expression.column().orElseThrow(), tables).dataType();
        if (expression.kind() == ExpressionKind.SUBQUERY) {
            SubquerySpec sub = expression.subquery().orElseThrow();
            if (sub.type() == SubqueryType.SCALAR) {
                if (sub.root().outputColumns().size() != 1) {
                    throw QoErrors.semantic("SCALAR subquery must project exactly 1 column");
                }
                return sub.root().outputColumns().get(0).dataType();
            }
            return DataType.INTEGER;
        }
        ExpressionOperator op = expression.operator().orElseThrow();
        if (op == ExpressionOperator.AND) {
            for (Expression child : expression.operands()) if (!isBoolean(child))
                throw QoErrors.semantic("AND operands must be boolean; found " + expressionContext(child));
            return DataType.INTEGER;
        }
        if (op == ExpressionOperator.NEGATE) {
            DataType type = expressionType(expression.operands().get(0), tables);
            if (!numeric(type)) throw QoErrors.semantic("unary minus requires a numeric operand; found " + expressionContext(expression));
            return type;
        }
        DataType left = expressionType(expression.operands().get(0), tables);
        DataType right = expressionType(expression.operands().get(1), tables);
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
        if (expression.kind() == ExpressionKind.SUBQUERY) {
            SubqueryType st = expression.subquery().map(SubquerySpec::type).orElse(null);
            return st == SubqueryType.EXISTS || st == SubqueryType.IN;
        }
        if (expression.kind() == ExpressionKind.BINARY) {
            ExpressionOperator op = expression.operator().orElse(null);
            if (op == ExpressionOperator.AND || isComparison(op)) return true;
        }
        return false;
    }

    private void validateComparisonTree(Expression expression, List<TableSchema> tables) {
        if (expression.kind() == ExpressionKind.BINARY && isComparison(expression.operator().orElse(null))) {
            DataType left = expressionType(expression.operands().get(0), tables);
            DataType right = expressionType(expression.operands().get(1), tables);
            if (!compatible(left, right))
                throw QoErrors.semantic("comparison operands have incompatible types; found " + expressionContext(expression));
        }
        expression.operands().forEach(op -> validateComparisonTree(op, tables));
    }

    private ColumnRef resolve(ColumnRef ref, List<TableSchema> localTables, List<TableSchema> outerTables) {
        List<TableSchema> candidates = findCandidates(ref, localTables);
        if (candidates.isEmpty() && !outerTables.isEmpty()) {
            candidates = findCandidates(ref, outerTables);
        }
        if (candidates.isEmpty()) throw QoErrors.semantic("unknown column '" + render(ref) + "'");
        if (candidates.size() > 1) throw QoErrors.semantic("ambiguous column '" + ref.columnName() + "'");
        ColumnSchema col = column(candidates.get(0), ref.columnName());
        return ColumnRef.of(candidates.get(0).tableName(), col.name());
    }

    private List<TableSchema> findCandidates(ColumnRef ref, List<TableSchema> tables) {
        return tables.stream()
                .filter(t -> ref.tableName().isEmpty() || same(t.tableName(), ref.tableName().get()))
                .filter(t -> t.columns().stream().anyMatch(c -> same(c.name(), ref.columnName())))
                .toList();
    }

    private List<TableSchema> scope(PlanNode node) {
        Map<String, TableSchema> found = new LinkedHashMap<>();
        collectSchemas(node, found);
        return List.copyOf(found.values());
    }

    private void collectSchemas(PlanNode node, Map<String, TableSchema> found) {
        node.scan().ifPresent(s -> {
            TableSchema schema = table(s.tableName());
            String scopeName = s.alias().orElse(schema.tableName());
            TableSchema scopedSchema = new TableSchema(
                    key(scopeName),
                    scopeName,
                    schema.columns(),
                    schema.primaryKey(),
                    schema.foreignKeys());
            found.putIfAbsent(scopedSchema.tableId(), scopedSchema);
        });
        node.children().forEach(child -> collectSchemas(child, found));
    }

    private List<TableSchema> mergeScope(List<TableSchema> left, List<TableSchema> right) {
        Map<String, TableSchema> merged = new LinkedHashMap<>();
        left.forEach(t -> merged.put(t.tableId(), t));
        right.forEach(t -> merged.putIfAbsent(t.tableId(), t));
        return List.copyOf(merged.values());
    }

    private Map<String, ColumnSchema> uniqueOutputNames(List<ResultColumn> columns, List<TableSchema> scope) {
        Map<String, ColumnSchema> result = new LinkedHashMap<>();
        for (ResultColumn output : columns) {
            ColumnRef source = output.source().orElseThrow(() -> QoErrors.semantic("NATURAL JOIN requires resolved columns"));
            result.putIfAbsent(key(output.name()), columnSchema(source, scope));
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

    private ColumnSchema columnSchema(ColumnRef ref, List<TableSchema> tables) {
        String qualifier = ref.tableName().orElseThrow(() -> QoErrors.semantic("unqualified column is not bound"));
        Optional<TableSchema> inScope = tables.stream()
                .filter(t -> same(t.tableName(), qualifier))
                .findFirst();
        TableSchema schema = inScope.orElseGet(() -> table(qualifier));
        return column(schema, ref.columnName());
    }

    private ColumnSchema column(TableSchema schema, String name) {
        return schema.columns().stream().filter(c -> same(c.name(), name)).findFirst()
                .orElseThrow(() -> QoErrors.semantic("unknown column '" + name + "' in table '" + schema.tableName() + "'"));
    }

    private static boolean compatible(DataType a, DataType b) {
        return a == b || (numeric(a) && numeric(b)) || (textual(a) && textual(b));
    }

    private static boolean numeric(DataType type) { return type == DataType.INTEGER || type == DataType.FLOAT; }
    private static boolean textual(DataType type) { return type == DataType.CHAR || type == DataType.VARCHAR; }
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

    private boolean isCorrelated(PlanNode subPlan, List<TableSchema> innerScope, List<TableSchema> outerScope) {
        if (subPlan.predicate().isPresent() && exprReferencesOuter(subPlan.predicate().get(), innerScope, outerScope)) {
            return true;
        }
        if (subPlan.join().flatMap(JoinSpec::condition).isPresent()
                && exprReferencesOuter(subPlan.join().get().condition().get(), innerScope, outerScope)) {
            return true;
        }
        return subPlan.children().stream().anyMatch(c -> isCorrelated(c, innerScope, outerScope));
    }

    private boolean exprReferencesOuter(Expression expr, List<TableSchema> innerScope, List<TableSchema> outerScope) {
        if (expr.kind() == ExpressionKind.COLUMN && expr.column().isPresent()) {
            String tbl = expr.column().get().tableName().orElse("");
            boolean inInner = innerScope.stream().anyMatch(t -> same(t.tableName(), tbl));
            boolean inOuter = outerScope.stream().anyMatch(t -> same(t.tableName(), tbl));
            if (!inInner && inOuter) return true;
        }
        return expr.operands().stream().anyMatch(op -> exprReferencesOuter(op, innerScope, outerScope));
    }

    private PlanNode copy(PlanNode n, List<PlanNode> children, List<ResultColumn> outputs, Optional<ScanSpec> scan,
                          Optional<Expression> predicate, List<ColumnRef> projection, Optional<JoinSpec> join,
                          Optional<SortSpec> sort, Optional<MutationSpec> mutation, Optional<IndexMetadata> createIndex) {
        return new PlanNode(n.kind(), children, outputs, n.estimatedRows(), scan, predicate, projection, join, sort,
                mutation, createIndex, n.limit(), n.createTable(), n.dropTable());
    }
}
