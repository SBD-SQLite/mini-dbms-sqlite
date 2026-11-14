package sqlike.qo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sqlike.shared.Expression;
import sqlike.shared.JoinSpec;
import sqlike.shared.MutationSpec;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.SubquerySpec;

/**
 * Final shape clean-up for projections: adjacent PROJECT nodes are merged and a PROJECT is dropped when it is
 * identical to its input (same columns, qualifiers and order), so it cannot be the only thing fixing the layout.
 * Subquery plans carried by expressions are cleaned the same way; their result layout never changes.
 */
final class ProjectionCleanup {
    private ProjectionCleanup() { }

    static PlanNode normalize(PlanNode plan) {
        List<PlanNode> children = plan.children().stream().map(ProjectionCleanup::normalize).toList();
        PlanNode current = withNormalizedSubqueries(QO5Plans.withChildren(plan, children));
        if (current.kind() != PlanNodeKind.PROJECT) return current;
        // PROJECT(P, PROJECT(Q, x)) = PROJECT(P, x) whenever P only asks for columns Q keeps.
        while (current.children().get(0).kind() == PlanNodeKind.PROJECT
                && !current.outputColumns().isEmpty() && !current.children().get(0).outputColumns().isEmpty()
                && current.children().get(0).projection().containsAll(current.projection())) {
            current = QO5Plans.withChildren(current, current.children().get(0).children());
        }
        return QO5Plans.isIdentityProject(current) ? current.children().get(0) : current;
    }

    private static PlanNode withNormalizedSubqueries(PlanNode node) {
        Optional<Expression> predicate = node.predicate().map(ProjectionCleanup::normalizeSubqueries);
        Optional<JoinSpec> join = node.join().map(spec -> new JoinSpec(spec.joinType(),
                spec.condition().map(ProjectionCleanup::normalizeSubqueries), spec.algorithm(), spec.commonColumns()));
        Optional<MutationSpec> mutation = node.mutation().map(spec -> new MutationSpec(spec.tableName(), spec.insertValues(),
                spec.updateColumn(), spec.updateExpression().map(ProjectionCleanup::normalizeSubqueries)));
        if (predicate.equals(node.predicate()) && join.equals(node.join()) && mutation.equals(node.mutation())) return node;
        return new PlanNode(node.kind(), node.children(), node.outputColumns(), node.estimatedRows(), node.scan(),
                predicate, node.projection(), join, node.sort(), mutation, node.createIndex(), node.limit(),
                node.createTable(), node.dropTable());
    }

    private static Expression normalizeSubqueries(Expression expression) {
        List<Expression> operands = new ArrayList<>();
        for (Expression operand : expression.operands()) operands.add(normalizeSubqueries(operand));
        Optional<SubquerySpec> subquery = expression.subquery().map(spec ->
                new SubquerySpec(spec.type(), normalize(spec.root()), spec.correlated()));
        if (operands.equals(expression.operands()) && subquery.equals(expression.subquery())) return expression;
        return new Expression(expression.kind(), expression.literal(), expression.column(), expression.operator(),
                operands, subquery);
    }
}
