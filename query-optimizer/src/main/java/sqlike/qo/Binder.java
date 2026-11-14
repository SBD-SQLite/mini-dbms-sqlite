package sqlike.qo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import sqlike.contract.IStorageManager;
import sqlike.shared.*;

public final class Binder {
    private final IStorageManager storage;
    /** Enclosing query levels visible to the subquery being bound, nearest level first. */
    private final ThreadLocal<List<List<Relation>>> outerScope = ThreadLocal.withInitial(List::of);
    /** One probe per subquery currently being bound; records whether it referenced a relation from outside itself. */
    private final ThreadLocal<List<CorrelationProbe>> probes = ThreadLocal.withInitial(ArrayList::new);

    public Binder(IStorageManager storage) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        this.storage = storage;
    }

    public PlanNode bind(ParsedQuery query) {
        if (query == null) throw QoErrors.invalidValue("ParsedQuery is null", 0);
        PlanNode root = query.root().orElseThrow(() -> QoErrors.invalidState("statement has no plan root"));
        PlanNodes.validate(root);
        outerScope.set(List.of());
        probes.get().clear();
        try {
            return bindNode(root);
        } finally {
            outerScope.remove();
            probes.remove();
        }
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
            case LIMIT -> bindLimit(node);
            case CREATE_TABLE, DROP_TABLE -> throw QoErrors.unsupported(node.kind() + " binding is not implemented", 0);
        };
    }

    private PlanNode bindScan(PlanNode node) {
        ScanSpec scan = node.scan().orElseThrow();
        TableSchema schema = table(scan.tableName());
        Optional<String> indexName = scan.indexName();
        if (node.kind() == PlanNodeKind.INDEX_SCAN) {
            IndexMetadata index = storage.getIndexes(schema.tableName()).stream()
                    .filter(i -> i.indexName().equalsIgnoreCase(scan.indexName().orElseThrow()))
                    .findFirst()
                    .orElseThrow(() -> QoErrors.semantic("unknown index '" + scan.indexName().orElseThrow() + "'"));
            indexName = Optional.of(index.indexName());
        }

        ScanSpec boundScan = new ScanSpec(schema.tableName(), indexName, scan.condition(), scan.alias());
        String qualifier = scan.alias().orElse(schema.tableName());
        List<ResultColumn> outputs = schema.columns().stream()
                .map(c -> new ResultColumn(c.name(), Optional.of(Refs.column(qualifier, c.name())), c.dataType()))
                .toList();
        return copy(node, List.of(), outputs, Optional.of(boundScan), node.predicate(), node.projection(), node.join(), node.sort(), node.mutation(), node.createIndex());
    }

    private PlanNode bindFilter(PlanNode node) {
        PlanNode child = bindNode(node.children().get(0));
        List<Relation> relations = scope(child);
        Expression predicate = bindExpression(node.predicate().orElseThrow(), relations);
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
                List<Relation> relations = scope(child);
                if (ref.tableName().isPresent() && relations.stream().noneMatch(r -> same(r.qualifier(), ref.tableName().get())))
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
                ColumnSchema column = columnSchema(resolved, scope(child));
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
        validateUniqueAliases(left, right);
        List<String> common = spec.commonColumns();
        Optional<Expression> condition = spec.condition();
        if (spec.joinType() == JoinType.NATURAL) {
            Map<String, List<ResultColumn>> leftNames = outputsByName(left.outputColumns());
            Map<String, List<ResultColumn>> rightNames = outputsByName(right.outputColumns());
            common = leftNames.keySet().stream().filter(rightNames::containsKey).toList();
            for (String name : common) {
                // Only a column name shared by both sides must be unique on each side; other repeated names are irrelevant.
                if (leftNames.get(name).size() > 1 || rightNames.get(name).size() > 1)
                    throw QoErrors.semantic("ambiguous natural join column '" + leftNames.get(name).get(0).name() + "'");
                if (!compatible(leftNames.get(name).get(0).dataType(), rightNames.get(name).get(0).dataType()))
                    throw QoErrors.semantic("incompatible types for NATURAL JOIN column '" + leftNames.get(name).get(0).name() + "'");
            }
        } else {
            List<Relation> joinRelations = mergeScope(scope(left), scope(right));
            if (condition.isPresent()) {
                condition = Optional.of(bindExpression(condition.get(), joinRelations));
                if (!isBoolean(condition.get()))
                    throw QoErrors.semantic("JOIN ON condition must be boolean; found " + expressionContext(condition.get()));
                validateComparisonTree(condition.get(), joinRelations);
            }
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

    private PlanNode bindLimit(PlanNode node) {
        PlanNode child = bindNode(node.children().get(0));
        return copy(node, List.of(child), child.outputColumns(), node.scan(), node.predicate(), node.projection(), node.join(),
                node.sort(), node.mutation(), node.createIndex());
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
            mutation = new MutationSpec(schema.tableName(), mutation.insertValues(), mutation.updateColumn(), mutation.updateExpression());
            return copy(node, List.of(), List.of(), node.scan(), node.predicate(), node.projection(), node.join(), node.sort(), Optional.of(mutation), node.createIndex());
        }
        PlanNode child = bindNode(node.children().get(0));
        if (node.kind() == PlanNodeKind.UPDATE) {
            ColumnRef target = resolve(mutation.updateColumn().orElseThrow(), scope(child));
            Expression value = bindExpression(mutation.updateExpression().orElseThrow(), scope(child));
            List<Relation> relations = scope(child);
            if (!compatible(columnSchema(target, relations).dataType(), expressionType(value, relations)))
                throw QoErrors.semantic("UPDATE expression type does not match column '" + render(target) + "'");
            mutation = new MutationSpec(schema.tableName(), mutation.insertValues(), Optional.of(target), Optional.of(value));
        } else {
            mutation = new MutationSpec(schema.tableName(), mutation.insertValues(), mutation.updateColumn(), mutation.updateExpression());
        }
        return copy(node, List.of(child), child.outputColumns(), node.scan(), node.predicate(), node.projection(), node.join(), node.sort(), Optional.of(mutation), node.createIndex());
    }

    private PlanNode bindCreateIndex(PlanNode node) {
        IndexMetadata index = node.createIndex().orElseThrow();
        TableSchema schema = table(index.tableName());
        ColumnSchema column = column(schema, index.columnName());
        IndexMetadata boundIndex = new IndexMetadata(index.indexName(), schema.tableName(), column.name(), index.indexType());
        return copy(node, node.children(), node.outputColumns(), node.scan(), node.predicate(), node.projection(), node.join(), node.sort(), node.mutation(), Optional.of(boundIndex));
    }

    private Expression bindExpression(Expression expression, List<Relation> relations) {
        if (expression.kind() == ExpressionKind.SUBQUERY) {
            SubquerySpec spec = expression.subquery()
                    .orElseThrow(() -> QoErrors.invalidState("subquery expression is missing its plan"));
            // The subquery sees its own FROM first, then this query's relations, then the levels above this query.
            List<List<Relation>> levels = new ArrayList<>();
            levels.add(relations);
            levels.addAll(outerScope.get());
            CorrelationProbe probe = new CorrelationProbe(levels.stream().flatMap(List::stream).toList());
            probes.get().add(probe);
            PlanNode boundRoot;
            try {
                boundRoot = withOuterScope(List.copyOf(levels), () -> bindNode(spec.root()));
            } finally {
                probes.get().remove(probes.get().size() - 1);
            }
            int outputCount = boundRoot.outputColumns().size();
            if ((spec.type() == SubqueryType.IN || spec.type() == SubqueryType.SCALAR) && outputCount != 1)
                throw QoErrors.semantic(spec.type() + " subquery must return exactly one column");

            List<Expression> operands = expression.operands().stream()
                    .map(operand -> bindExpression(operand, relations)).toList();
            if (spec.type() == SubqueryType.IN) {
                DataType leftType = expressionType(operands.get(0), relations);
                DataType subqueryType = boundRoot.outputColumns().get(0).dataType();
                if (!compatible(leftType, subqueryType))
                    throw QoErrors.semantic("IN operand type is incompatible with subquery result column");
            }
            // correlated is derived from what the subquery actually referenced, not from the flag it arrived with.
            SubquerySpec boundSpec = new SubquerySpec(spec.type(), boundRoot, probe.hit);
            return new Expression(expression.kind(), expression.literal(), expression.column(), expression.operator(),
                    operands, Optional.of(boundSpec));
        }
        if (expression.kind() == ExpressionKind.COLUMN) {
            ColumnRef resolved = resolve(expression.column().orElseThrow(), relations);
            return new Expression(expression.kind(), expression.literal(), Optional.of(resolved), expression.operator(), expression.operands());
        }
        List<Expression> operands = expression.operands().stream().map(e -> bindExpression(e, relations)).toList();
        Expression bound = new Expression(expression.kind(), expression.literal(), expression.column(), expression.operator(), operands);
        expressionType(bound, relations);
        return bound;
    }

    private DataType expressionType(Expression expression, List<Relation> relations) {
        if (expression.kind() == ExpressionKind.LITERAL) return expression.literal().orElseThrow().type();
        if (expression.kind() == ExpressionKind.COLUMN) return columnSchema(expression.column().orElseThrow(), relations).dataType();
        if (expression.kind() == ExpressionKind.SUBQUERY) {
            SubquerySpec spec = expression.subquery()
                    .orElseThrow(() -> QoErrors.invalidState("subquery expression is missing its plan"));
            return switch (spec.type()) {
                case EXISTS, IN -> DataType.INTEGER;
                case SCALAR -> {
                    List<ResultColumn> outputs = spec.root().outputColumns();
                    if (outputs.size() != 1)
                        throw QoErrors.semantic("SCALAR subquery must return exactly one column");
                    yield outputs.get(0).dataType();
                }
            };
        }
        ExpressionOperator op = expression.operator()
                .orElseThrow(() -> QoErrors.invalidState("expression is missing an operator"));
        if (op == ExpressionOperator.AND) {
            for (Expression child : expression.operands()) if (!isBoolean(child))
                throw QoErrors.semantic("AND operands must be boolean; found " + expressionContext(child));
            return DataType.INTEGER;
        }
        if (op == ExpressionOperator.NEGATE) {
            DataType type = expressionType(expression.operands().get(0), relations);
            if (!numeric(type)) throw QoErrors.semantic("unary minus requires a numeric operand; found " + expressionContext(expression));
            return type;
        }
        DataType left = expressionType(expression.operands().get(0), relations);
        DataType right = expressionType(expression.operands().get(1), relations);
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
            return expression.subquery().map(spec -> spec.type() == SubqueryType.EXISTS || spec.type() == SubqueryType.IN)
                    .orElse(false);
        }
        if (expression.kind() == ExpressionKind.BINARY) {
            ExpressionOperator op = expression.operator().orElse(null);
            if (op == ExpressionOperator.AND || isComparison(op)) return true;
        }
        return false;
    }

    private void validateComparisonTree(Expression expression, List<Relation> relations) {
        if (expression.kind() == ExpressionKind.BINARY && isComparison(expression.operator().orElse(null))) {
            DataType left = expressionType(expression.operands().get(0), relations);
            DataType right = expressionType(expression.operands().get(1), relations);
            if (!compatible(left, right))
                throw QoErrors.semantic("comparison operands have incompatible types; found " + expressionContext(expression));
        }
        expression.operands().forEach(operand -> validateComparisonTree(operand, relations));
    }

    private ColumnRef resolve(ColumnRef ref, List<Relation> relations) {
        List<Relation> candidates = matchingRelations(ref, relations);
        boolean outer = false;
        if (candidates.isEmpty()) {
            // Nearest enclosing level wins; ambiguity is only judged inside one level.
            for (List<Relation> level : outerScope.get()) {
                candidates = matchingRelations(ref, level);
                if (!candidates.isEmpty()) { outer = true; break; }
            }
        }
        if (candidates.isEmpty()) throw QoErrors.semantic("unknown column '" + render(ref) + "'");
        if (candidates.size() > 1) throw QoErrors.semantic("ambiguous column '" + ref.columnName() + "'");
        if (outer) for (CorrelationProbe probe : probes.get()) probe.note(candidates.get(0));
        ColumnSchema col = column(candidates.get(0).schema(), ref.columnName());
        return Refs.column(candidates.get(0).qualifier(), col.name());
    }

    private List<Relation> scope(PlanNode node) {
        List<Relation> found = new ArrayList<>();
        collectRelations(node, found);
        return List.copyOf(found);
    }

    private void collectRelations(PlanNode node, List<Relation> found) {
        node.scan().ifPresent(scan -> {
            TableSchema schema = table(scan.tableName());
            String qualifier = scan.alias().orElse(schema.tableName());
            if (found.stream().anyMatch(r -> same(r.qualifier(), qualifier)))
                throw QoErrors.semantic("duplicate table alias or qualifier '" + qualifier + "'");
            found.add(new Relation(schema, qualifier, Set.of()));
        });
        boolean natural = node.join().filter(spec -> spec.joinType() == JoinType.NATURAL).isPresent() && node.children().size() == 2;
        if (!natural) {
            node.children().forEach(child -> collectRelations(child, found));
            return;
        }
        // The common columns of a NATURAL JOIN appear once, as the left side's column; the right side's copy is hidden
        // from unqualified references (it can still be named with its own qualifier).
        collectRelations(node.children().get(0), found);
        int rightStart = found.size();
        collectRelations(node.children().get(1), found);
        Set<String> merged = new HashSet<>(node.join().orElseThrow().commonColumns().stream().map(Binder::key).toList());
        for (int i = rightStart; i < found.size(); i++) {
            Relation relation = found.get(i);
            Set<String> hidden = new HashSet<>(relation.hiddenColumns());
            hidden.addAll(merged);
            found.set(i, new Relation(relation.schema(), relation.qualifier(), Set.copyOf(hidden)));
        }
    }

    private void validateUniqueAliases(PlanNode left, PlanNode right) {
        List<String> aliases = new ArrayList<>();
        collectAliases(left, aliases);
        collectAliases(right, aliases);
    }

    private void collectAliases(PlanNode node, List<String> aliases) {
        node.scan().flatMap(ScanSpec::alias).ifPresent(alias -> {
            if (aliases.stream().anyMatch(existing -> same(existing, alias)))
                throw QoErrors.semantic("duplicate table alias or qualifier '" + alias + "'");
            aliases.add(alias);
        });
        node.children().forEach(child -> collectAliases(child, aliases));
    }

    private List<Relation> mergeScope(List<Relation> left, List<Relation> right) {
        List<Relation> merged = new ArrayList<>(left);
        for (Relation relation : right) {
            if (merged.stream().anyMatch(existing -> same(existing.qualifier(), relation.qualifier())))
                throw QoErrors.semantic("duplicate table alias or qualifier '" + relation.qualifier() + "'");
            merged.add(relation);
        }
        return List.copyOf(merged);
    }

    private <T> T withOuterScope(List<List<Relation>> scope, Supplier<T> action) {
        List<List<Relation>> previous = outerScope.get();
        outerScope.set(scope);
        try {
            return action.get();
        } finally {
            outerScope.set(previous);
        }
    }

    private List<Relation> matchingRelations(ColumnRef ref, List<Relation> relations) {
        return relations.stream()
                .filter(r -> ref.tableName().isEmpty() || same(r.qualifier(), ref.tableName().get()))
                .filter(r -> r.schema().columns().stream().anyMatch(c -> same(c.name(), ref.columnName())))
                .filter(r -> ref.tableName().isPresent() || !r.hiddenColumns().contains(key(ref.columnName())))
                .toList();
    }

    /** Output columns grouped by lower-case name, in first-occurrence order. */
    private Map<String, List<ResultColumn>> outputsByName(List<ResultColumn> columns) {
        Map<String, List<ResultColumn>> result = new LinkedHashMap<>();
        for (ResultColumn output : columns) {
            output.source().orElseThrow(() -> QoErrors.semantic("NATURAL JOIN requires resolved columns"));
            result.computeIfAbsent(key(output.name()), name -> new ArrayList<>()).add(output);
        }
        return result;
    }

    private TableSchema table(String name) {
        String actualName = storage.listTables().stream()
                .filter(tableName -> same(tableName, name))
                .findFirst()
                .orElseThrow(() -> QoErrors.semantic("unknown table '" + name + "'"));
        return storage.getSchema(actualName);
    }

    private ColumnSchema columnSchema(ColumnRef ref, List<Relation> relations) {
        String qualifier = ref.tableName().orElseThrow(() -> QoErrors.semantic("unqualified column is not bound"));
        Relation relation = relations.stream().filter(r -> same(r.qualifier(), qualifier)).findFirst()
                .or(() -> outerScope.get().stream().flatMap(List::stream).filter(r -> same(r.qualifier(), qualifier)).findFirst())
                .orElseThrow(() -> QoErrors.semantic("unknown table or alias '" + qualifier + "'"));
        return column(relation.schema(), ref.columnName());
    }

    private ColumnSchema column(TableSchema schema, String name) {
        return schema.columns().stream().filter(c -> same(c.name(), name)).findFirst()
                .orElseThrow(() -> QoErrors.semantic("unknown column '" + name + "' in table '" + schema.tableName() + "'"));
    }

    private static boolean compatible(DataType a, DataType b) {
        return a == b || numeric(a) && numeric(b) || text(a) && text(b);
    }

    private static boolean numeric(DataType type) { return type == DataType.INTEGER || type == DataType.FLOAT; }
    private static boolean text(DataType type) { return type == DataType.CHAR || type == DataType.VARCHAR; }
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

    /** {@code hiddenColumns}: lower-case names merged away by a NATURAL JOIN; unqualified references skip this relation for them. */
    private record Relation(TableSchema schema, String qualifier, Set<String> hiddenColumns) { }

    /** {@code outside} = every relation visible from outside the subquery; a hit on one of them makes the subquery correlated. */
    private static final class CorrelationProbe {
        private final List<Relation> outside;
        private boolean hit;

        CorrelationProbe(List<Relation> outside) { this.outside = outside; }

        void note(Relation relation) {
            if (outside.stream().anyMatch(candidate -> candidate == relation)) hit = true;
        }
    }

    private PlanNode copy(PlanNode n, List<PlanNode> children, List<ResultColumn> outputs, Optional<ScanSpec> scan,
                          Optional<Expression> predicate, List<ColumnRef> projection, Optional<JoinSpec> join,
                          Optional<SortSpec> sort, Optional<MutationSpec> mutation, Optional<IndexMetadata> createIndex) {
        return new PlanNode(n.kind(), children, outputs, n.estimatedRows(), scan, predicate, projection, join, sort, mutation, createIndex,
                n.limit(), n.createTable(), n.dropTable());
    }
}
