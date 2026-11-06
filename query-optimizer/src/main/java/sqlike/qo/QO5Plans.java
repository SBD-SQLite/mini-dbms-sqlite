package sqlike.qo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import sqlike.shared.*;

/** Internal QO5 tree operations. Never mutates shared DTOs or rebinds against the catalog. */
final class QO5Plans {
    private QO5Plans() { }

    static boolean isJoin(PlanNode node) {
        return node.kind() == PlanNodeKind.JOIN || node.kind() == PlanNodeKind.CARTESIAN;
    }

    static PlanNode requirePlan(PlanNode plan) {
        if (plan == null || plan.kind() == null) throw QoErrors.invalidState("QO5 requires a plan with a node kind");
        PlanNodes.validate(plan);
        return plan;
    }

    static boolean hasSubquery(Expression expression) {
        return expression.kind() == ExpressionKind.SUBQUERY
                || expression.operands().stream().anyMatch(QO5Plans::hasSubquery);
    }

    static List<Expression> conjuncts(Expression expression) {
        List<Expression> result = new ArrayList<>();
        split(expression, result);
        return result;
    }

    private static void split(Expression expression, List<Expression> result) {
        if (expression.kind() == ExpressionKind.BINARY
                && expression.operator().orElse(null) == ExpressionOperator.AND) {
            expression.operands().forEach(child -> split(child, result));
        } else result.add(expression);
    }

    static Expression binary(ExpressionOperator operator, Expression left, Expression right) {
        return new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(),
                Optional.of(operator), List.of(left, right));
    }

    static Optional<Expression> conjunction(List<Expression> expressions) {
        if (expressions.isEmpty()) return Optional.empty();
        Expression result = expressions.get(0);
        for (int i = 1; i < expressions.size(); i++) result = binary(ExpressionOperator.AND, result, expressions.get(i));
        return Optional.of(result);
    }

    static String key(ColumnRef ref) {
        if (ref.tableName().isEmpty()) throw QoErrors.invalidState("QO5 requires qualified source columns");
        // Length prefix prevents collisions when identifiers contain separator characters.
        String table = ref.tableName().get().toLowerCase(Locale.ROOT);
        return table.length() + ":" + table + ref.columnName().toLowerCase(Locale.ROOT);
    }

    static Set<String> columns(Expression expression) {
        Set<String> result = new LinkedHashSet<>();
        expression.column().ifPresent(ref -> result.add(key(ref)));
        for (Expression child : expression.operands()) result.addAll(columns(child));
        return result;
    }

    static Set<String> available(PlanNode node) {
        Set<String> result = new LinkedHashSet<>();
        for (ResultColumn column : node.outputColumns()) column.source().ifPresent(ref -> result.add(key(ref)));
        return result;
    }

    /** Resolve NATURAL equality using the original child layouts, before any reordering. */
    static List<Expression> joinConditions(PlanNode node) {
        JoinSpec spec = node.join().orElseThrow();
        List<Expression> result = new ArrayList<>();
        if (spec.joinType() == JoinType.NATURAL) {
            for (String name : spec.commonColumns()) {
                ColumnRef left = namedColumn(node.children().get(0), name);
                ColumnRef right = namedColumn(node.children().get(1), name);
                result.add(binary(ExpressionOperator.EQ, column(left), column(right)));
            }
        }
        spec.condition().ifPresent(condition -> result.addAll(conjuncts(condition)));
        return result;
    }

    private static ColumnRef namedColumn(PlanNode node, String name) {
        List<ResultColumn> matches = node.outputColumns().stream()
                .filter(column -> column.name().equalsIgnoreCase(name)).toList();
        if (matches.size() != 1 || matches.get(0).source().isEmpty())
            throw QoErrors.invalidState("QO5 cannot resolve NATURAL JOIN column '" + name + "'");
        return matches.get(0).source().get();
    }

    private static Expression column(ColumnRef ref) {
        return new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(ref), Optional.empty(), List.of());
    }

    static PlanNode join(PlanNode left, PlanNode right, List<Expression> conditions) {
        Optional<Expression> predicate = conjunction(conditions);
        JoinSpec spec = new JoinSpec(predicate.isPresent() ? JoinType.THETA : JoinType.CARTESIAN,
                predicate, JoinAlgorithm.BLOCK_NESTED_LOOP, List.of());
        List<ResultColumn> outputs = new ArrayList<>(left.outputColumns());
        outputs.addAll(right.outputColumns());
        PlanNode node = PlanNodes.join(left, right, spec);
        return copy(node, node.children(), outputs);
    }

    static PlanNode filter(PlanNode child, Expression expression) {
        PlanNode filter = PlanNodes.filter(child, expression);
        return copy(filter, filter.children(), child.outputColumns());
    }

    static PlanNode restore(PlanNode candidate, PlanNode original) {
        if (candidate.outputColumns().equals(original.outputColumns())) return candidate;
        if (original.outputColumns().isEmpty())
            throw QoErrors.invalidState("QO5 requires bound output metadata for join reordering");
        Set<String> available = available(candidate);
        List<ColumnRef> projection = new ArrayList<>();
        for (ResultColumn output : original.outputColumns()) {
            ColumnRef source = output.source().orElseThrow(() -> QoErrors.invalidState("QO5 output has no source"));
            if (!available.contains(key(source))) throw QoErrors.invalidState("QO5 candidate lost an output column");
            projection.add(source);
        }
        PlanNode project = PlanNodes.project(candidate, List.copyOf(projection));
        return copy(project, project.children(), original.outputColumns());
    }

    static PlanNode withChildren(PlanNode node, List<PlanNode> children) {
        if (node.children().equals(children)) return node;
        List<ResultColumn> outputs = node.outputColumns();
        if (node.kind() == PlanNodeKind.FILTER || node.kind() == PlanNodeKind.SORT || node.kind() == PlanNodeKind.LIMIT)
            outputs = children.get(0).outputColumns();
        // Each transformed child restores its original layout, so PROJECT/JOIN layouts remain valid.
        return copy(node, children, outputs);
    }

    private static PlanNode copy(PlanNode node, List<PlanNode> children, List<ResultColumn> outputs) {
        return new PlanNode(node.kind(), List.copyOf(children), List.copyOf(outputs), Optional.empty(),
                node.scan(), node.predicate(), node.projection(), node.join(), node.sort(), node.mutation(),
                node.createIndex(), node.limit(), node.createTable(), node.dropTable());
    }
}
