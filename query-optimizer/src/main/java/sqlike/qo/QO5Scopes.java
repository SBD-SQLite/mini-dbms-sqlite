package sqlike.qo;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import sqlike.shared.*;

/** QO5-only scope traversal. Expressions retain bound identities and correlation flags. */
final class QO5Scopes {
    private QO5Scopes() { }

    /** Optimize expression-owned plans separately; never merge their relations into the caller. */
    static PlanNode mapSubqueries(PlanNode node, UnaryOperator<PlanNode> optimize) {
        PlanNode current = QO5Plans.withChildren(node,
                node.children().stream().map(child -> mapSubqueries(child, optimize)).toList());
        Optional<Expression> predicate = current.predicate().map(value -> mapExpression(value, optimize));
        var join = current.join().map(spec -> new JoinSpec(spec.joinType(),
                spec.condition().map(value -> mapExpression(value, optimize)), spec.algorithm(), spec.commonColumns()));
        var mutation = current.mutation().map(spec -> new MutationSpec(spec.tableName(), spec.insertValues(),
                spec.updateColumn(), spec.updateExpression().map(value -> mapExpression(value, optimize))));
        if (predicate.equals(current.predicate()) && join.equals(current.join()) && mutation.equals(current.mutation()))
            return current;
        return new PlanNode(current.kind(), current.children(), current.outputColumns(), Optional.empty(), current.scan(),
                predicate, current.projection(), join, current.sort(), mutation, current.createIndex(), current.limit(),
                current.createTable(), current.dropTable());
    }

    private static Expression mapExpression(Expression expression, UnaryOperator<PlanNode> optimize) {
        List<Expression> operands = expression.operands().stream().map(value -> mapExpression(value, optimize)).toList();
        var subquery = expression.subquery().map(spec -> {
            PlanNode root = optimize.apply(spec.root());
            if (!root.outputColumns().equals(spec.root().outputColumns()))
                throw QoErrors.invalidState("QO5 changed a subquery output schema");
            return new SubquerySpec(spec.type(), root, spec.correlated());
        });
        if (operands.equals(expression.operands()) && subquery.equals(expression.subquery())) return expression;
        return new Expression(expression.kind(), expression.literal(), expression.column(), expression.operator(), operands, subquery);
    }

    /** A barrier prevents flattening across this node; its children may still be optimized. */
    static boolean isJoinBoundary(PlanNode node) {
        if (node.kind() == PlanNodeKind.LIMIT) return true;
        Set<String> local = new HashSet<>();
        collectScanColumns(node, local);
        return node.predicate().map(value -> isScoped(value, local)).orElse(false)
                || node.join().flatMap(JoinSpec::condition).map(value -> isScoped(value, local)).orElse(false);
    }

    private static boolean isScoped(Expression expression, Set<String> local) {
        return QO5Plans.hasSubquery(expression) || !local.containsAll(QO5Plans.columns(expression));
    }

    private static void collectScanColumns(PlanNode node, Set<String> columns) {
        if (node.scan().isPresent()) columns.addAll(QO5Plans.available(node));
        node.children().forEach(child -> collectScanColumns(child, columns));
    }
}
