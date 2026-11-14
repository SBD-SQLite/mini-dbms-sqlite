package sqlike.qo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import sqlike.shared.*;

/** QO5's shared decoder for heuristic and genetic join search; never creates new shared contracts. */
final class JoinSearchSpace {
    enum Shape { LEFT, RIGHT, BUSHY }

    private final PlanNode original;
    private final List<PlanNode> inputs;
    private final List<Expression> predicates;

    private JoinSearchSpace(PlanNode original, List<PlanNode> inputs, List<Expression> predicates) {
        this.original = original;
        this.inputs = List.copyOf(inputs);
        this.predicates = List.copyOf(predicates);
    }

    static boolean isRegion(PlanNode plan) {
        if (QO5Plans.hasSubquery(plan)) return false;
        if (QO5Plans.isJoin(plan)) return true;
        return (plan.kind() == PlanNodeKind.PROJECT || plan.kind() == PlanNodeKind.FILTER)
                && isRegion(plan.children().get(0));
    }

    static Optional<JoinSearchSpace> from(PlanNode original) {
        if (!isRegion(original)) return Optional.empty();
        List<PlanNode> inputs = new ArrayList<>();
        List<Expression> predicates = new ArrayList<>();
        collect(original, inputs, predicates);
        if (inputs.size() < 2) return Optional.empty();
        Set<String> seen = new HashSet<>();
        for (PlanNode input : inputs) {
            if (input.outputColumns().isEmpty())
                throw QoErrors.invalidState("join search requires bound output metadata");
            for (String source : QO5Plans.available(input)) {
                if (!seen.add(source))
                    throw QoErrors.invalidState("join inputs need distinct bound relation identities");
            }
        }
        for (Expression predicate : predicates) {
            if (!seen.containsAll(QO5Plans.columns(predicate)))
                throw QoErrors.invalidState("join predicate references unavailable input columns");
        }
        return Optional.of(new JoinSearchSpace(original, inputs, predicates));
    }

    int size() { return inputs.size(); }

    List<Integer> originalOrder() {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < size(); i++) order.add(i);
        return List.copyOf(order);
    }

    /** Every input occurs exactly once; bound aliases distinguish self-join occurrences. */
    PlanNode decode(List<Integer> order, Shape shape) {
        if (order == null || shape == null || order.size() != size())
            throw QoErrors.invalidState("join order must be a complete permutation");
        boolean[] seen = new boolean[size()];
        for (Integer index : order) {
            if (index == null || index < 0 || index >= size() || seen[index])
                throw QoErrors.invalidState("join order must contain each input exactly once");
            seen[index] = true;
        }
        List<Expression> pending = new ArrayList<>(predicates);
        PlanNode result = build(order, 0, size(), shape, pending);
        for (Expression predicate : pending) result = QO5Plans.filter(result, predicate);
        result = QO5Plans.restore(result, original);
        PlanNodes.validate(result);
        return result;
    }

    private PlanNode build(List<Integer> order, int from, int to, Shape shape, List<Expression> pending) {
        if (to - from == 1) {
            PlanNode input = inputs.get(order.get(from));
            for (Expression predicate : takeAvailable(pending, QO5Plans.available(input)))
                input = QO5Plans.filter(input, predicate);
            return input;
        }
        int split = switch (shape) {
            case LEFT -> to - 1;
            case RIGHT -> from + 1;
            case BUSHY -> from + (to - from) / 2;
        };
        PlanNode left = build(order, from, split, shape, pending);
        PlanNode right = build(order, split, to, shape, pending);
        Set<String> available = new HashSet<>(QO5Plans.available(left));
        available.addAll(QO5Plans.available(right));
        return QO5Plans.join(left, right, takeAvailable(pending, available));
    }

    private static List<Expression> takeAvailable(List<Expression> pending, Set<String> available) {
        List<Expression> result = new ArrayList<>();
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Expression predicate = iterator.next();
            Set<String> refs = QO5Plans.columns(predicate);
            if (!refs.isEmpty() && available.containsAll(refs)) {
                result.add(predicate);
                iterator.remove();
            }
        }
        return result;
    }

    private static void collect(PlanNode node, List<PlanNode> inputs, List<Expression> predicates) {
        if (!isRegion(node)) {
            inputs.add(node);
        } else if (QO5Plans.isJoin(node)) {
            if (node.join().orElseThrow().algorithm() != JoinAlgorithm.BLOCK_NESTED_LOOP)
                throw QoErrors.invalidState("QO5 only supports BLOCK_NESTED_LOOP");
            predicates.addAll(QO5Plans.joinConditions(node));
            node.children().forEach(child -> collect(child, inputs, predicates));
        } else {
            node.predicate().ifPresent(predicate -> predicates.addAll(QO5Plans.conjuncts(predicate)));
            collect(node.children().get(0), inputs, predicates);
        }
    }
}
