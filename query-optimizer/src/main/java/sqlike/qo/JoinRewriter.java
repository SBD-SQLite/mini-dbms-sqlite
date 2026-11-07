package sqlike.qo;

import java.util.ArrayList;
import java.util.List;
import sqlike.shared.*;

/** QO-11 transformations on bound plans, preserving residual predicates and output layouts. */
public class JoinRewriter {
    public PlanNode cartesianToJoin(PlanNode plan) {
        return rewrite(QO5Plans.requirePlan(plan), false);
    }

    public PlanNode mergeSelectionIntoJoin(PlanNode plan) {
        return rewrite(QO5Plans.requirePlan(plan), true);
    }

    private PlanNode rewrite(PlanNode plan, boolean mergeJoins) {
        // LIMIT and subqueries are semantic boundaries, not predicates to redistribute.
        if (plan.kind() == PlanNodeKind.LIMIT) return plan;
        PlanNode current = QO5Plans.withChildren(plan,
                plan.children().stream().map(child -> rewrite(child, mergeJoins)).toList());
        if (current.kind() != PlanNodeKind.FILTER) return current;

        List<Expression> predicates = new ArrayList<>();
        PlanNode input = current;
        while (input.kind() == PlanNodeKind.FILTER) {
            predicates.addAll(QO5Plans.conjuncts(input.predicate().orElseThrow()));
            input = input.children().get(0);
        }
        // QO4 may insert a projection between the cross-table filter and the product.
        // Move only through projections that expose every referenced source column.
        PlanNode projectedInput = input;
        if (input.kind() == PlanNodeKind.PROJECT
                && leadsToJoin(input)
                && predicates.stream().noneMatch(QO5Plans::hasSubquery)
                && predicates.stream().allMatch(predicate -> QO5Plans.available(projectedInput.children().get(0))
                        .containsAll(QO5Plans.columns(predicate)))) {
            PlanNode below = input.children().get(0);
            for (Expression predicate : predicates) below = QO5Plans.filter(below, predicate);
            return QO5Plans.withChildren(input, List.of(rewrite(below, mergeJoins)));
        }
        if (!QO5Plans.isJoin(input) || (!mergeJoins && input.kind() != PlanNodeKind.CARTESIAN)) return current;
        var left = QO5Plans.available(input.children().get(0));
        var right = QO5Plans.available(input.children().get(1));
        var all = new java.util.HashSet<>(left);
        all.addAll(right);
        List<Expression> moved = new ArrayList<>(), residual = new ArrayList<>();
        for (Expression predicate : predicates) {
            if (QO5Plans.hasSubquery(predicate)) {
                residual.add(predicate);
                continue;
            }
            var refs = QO5Plans.columns(predicate);
            boolean connects = refs.stream().anyMatch(left::contains) && refs.stream().anyMatch(right::contains);
            if (all.containsAll(refs) && (input.kind() != PlanNodeKind.CARTESIAN || connects)) moved.add(predicate);
            else residual.add(predicate);
        }
        if (moved.isEmpty()) return current;
        List<Expression> conditions = QO5Plans.joinConditions(input);
        conditions.addAll(moved);
        PlanNode result = QO5Plans.restore(QO5Plans.join(input.children().get(0), input.children().get(1), conditions), input);
        for (Expression predicate : residual) result = QO5Plans.filter(result, predicate);
        return result;
    }

    private boolean leadsToJoin(PlanNode node) {
        if (QO5Plans.isJoin(node)) return true;
        return (node.kind() == PlanNodeKind.PROJECT || node.kind() == PlanNodeKind.FILTER)
                && leadsToJoin(node.children().get(0));
    }
}
