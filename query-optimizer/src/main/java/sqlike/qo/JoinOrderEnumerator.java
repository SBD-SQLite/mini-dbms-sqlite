package sqlike.qo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import sqlike.shared.*;

/**
 * Bounded, deterministic search over join permutations and left/right-deep and bushy shapes.
 * NATURAL joins are lowered to explicit bound equalities; a final projection preserves the original
 * representatives and column order. This avoids inferring new natural keys from intermediate schemas.
 */
public class JoinOrderEnumerator {
    private final int maxCandidates;
    // Above this size, use cyclic/reverse orders only rather than factorial permutation search.
    private static final int PERMUTATION_LIMIT = 8;

    /** At most 128 candidates per join region, including the unchanged baseline. */
    public JoinOrderEnumerator() { this(128); }

    public JoinOrderEnumerator(int maxCandidates) {
        if (maxCandidates < 2) throw new IllegalArgumentException("at least two candidates are required");
        this.maxCandidates = maxCandidates;
    }

    public List<PlanNode> enumerate(PlanNode plan) {
        QO5Plans.requirePlan(plan);
        return variants(plan);
    }

    private List<PlanNode> variants(PlanNode plan) {
        if (plan.kind() == PlanNodeKind.LIMIT || hasSubquery(plan)) return List.of(plan);
        if (isRegion(plan)) return enumerateRegion(plan);
        // Preserve SORT and other enclosing operations. Vary one child at a time within the budget.
        Set<PlanNode> result = new LinkedHashSet<>();
        result.add(plan);
        for (int i = 0; i < plan.children().size() && result.size() < maxCandidates; i++) {
            for (PlanNode child : variants(plan.children().get(i))) {
                List<PlanNode> children = new ArrayList<>(plan.children());
                children.set(i, child);
                result.add(QO5Plans.withChildren(plan, children));
                if (result.size() >= maxCandidates) break;
            }
        }
        return List.copyOf(result);
    }

    private List<PlanNode> enumerateRegion(PlanNode original) {
        List<PlanNode> atoms = new ArrayList<>();
        List<Expression> conditions = new ArrayList<>();
        collect(original, atoms, conditions);
        if (atoms.size() < 2) return List.of(original);
        Set<String> seen = new HashSet<>();
        for (PlanNode atom : atoms) {
            if (atom.outputColumns().isEmpty())
                throw QoErrors.invalidState("join enumeration requires bound output metadata");
            for (String source : QO5Plans.available(atom)) {
                if (!seen.add(source))
                    throw QoErrors.invalidState("join inputs need distinct bound relation identities");
            }
        }
        for (Expression condition : conditions) {
            if (!seen.containsAll(QO5Plans.columns(condition)))
                throw QoErrors.invalidState("join predicate references unavailable input columns");
        }
        Set<PlanNode> result = new LinkedHashSet<>();
        result.add(original);
        // Diversify outer relations before spending the remaining budget on permutations.
        List<PlanNode> order = new ArrayList<>(atoms);
        for (int i = 0; i < atoms.size() && result.size() < maxCandidates; i++) {
            addShapes(original, order, conditions, result);
            Collections.rotate(order, -1);
        }
        Collections.reverse(order);
        addShapes(original, order, conditions, result);
        if (atoms.size() <= PERMUTATION_LIMIT) {
            int[] permutation = new int[atoms.size()];
            for (int i = 0; i < permutation.length; i++) permutation[i] = i;
            do {
                List<PlanNode> permuted = new ArrayList<>();
                for (int index : permutation) permuted.add(atoms.get(index));
                addShapes(original, permuted, conditions, result);
            } while (result.size() < maxCandidates && nextPermutation(permutation));
        }
        return List.copyOf(result);
    }

    private void addShapes(PlanNode original, List<PlanNode> order, List<Expression> conditions, Set<PlanNode> result) {
        for (Shape shape : Shape.values()) {
            if (result.size() >= maxCandidates) return;
            List<Expression> pending = new ArrayList<>(conditions);
            PlanNode candidate = build(order, 0, order.size(), shape, pending);
            // Predicates without column references belong once at the region root.
            for (Expression residual : pending) candidate = QO5Plans.filter(candidate, residual);
            candidate = QO5Plans.restore(candidate, original);
            PlanNodes.validate(candidate);
            result.add(candidate);
        }
    }

    private PlanNode build(List<PlanNode> order, int from, int to, Shape shape, List<Expression> pending) {
        if (to - from == 1) {
            PlanNode atom = order.get(from);
            List<Expression> local = takeAvailable(pending, QO5Plans.available(atom));
            for (Expression predicate : local) atom = QO5Plans.filter(atom, predicate);
            return atom;
        }
        int split = switch (shape) {
            case LEFT -> to - 1;
            case RIGHT -> from + 1;
            case BUSHY -> from + (to - from) / 2;
        };
        PlanNode left = build(order, from, split, shape, pending);
        PlanNode right = build(order, split, to, shape, pending);
        Set<String> sources = new HashSet<>(QO5Plans.available(left));
        sources.addAll(QO5Plans.available(right));
        return QO5Plans.join(left, right, takeAvailable(pending, sources));
    }

    private List<Expression> takeAvailable(List<Expression> pending, Set<String> available) {
        List<Expression> result = new ArrayList<>();
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Expression expression = iterator.next();
            Set<String> refs = QO5Plans.columns(expression);
            if (!refs.isEmpty() && available.containsAll(refs)) {
                result.add(expression);
                iterator.remove();
            }
        }
        return result;
    }

    private boolean isRegion(PlanNode plan) {
        if (hasSubquery(plan)) return false;
        if (QO5Plans.isJoin(plan)) return true;
        return (plan.kind() == PlanNodeKind.PROJECT || plan.kind() == PlanNodeKind.FILTER)
                && isRegion(plan.children().get(0));
    }

    private void collect(PlanNode plan, List<PlanNode> atoms, List<Expression> conditions) {
        if (!isRegion(plan)) {
            atoms.add(plan);
        } else if (QO5Plans.isJoin(plan)) {
            if (plan.join().orElseThrow().algorithm() != JoinAlgorithm.BLOCK_NESTED_LOOP)
                throw QoErrors.invalidState("QO5 only supports BLOCK_NESTED_LOOP");
            conditions.addAll(QO5Plans.joinConditions(plan));
            plan.children().forEach(child -> collect(child, atoms, conditions));
        } else {
            plan.predicate().ifPresent(predicate -> conditions.addAll(QO5Plans.conjuncts(predicate)));
            collect(plan.children().get(0), atoms, conditions);
        }
    }

    private boolean hasSubquery(PlanNode plan) {
        return plan.predicate().map(QO5Plans::hasSubquery).orElse(false)
                || plan.join().flatMap(JoinSpec::condition).map(QO5Plans::hasSubquery).orElse(false)
                || plan.children().stream().anyMatch(this::hasSubquery);
    }

    private boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) pivot--;
        if (pivot < 0) return false;
        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) successor--;
        int temporary = values[pivot]; values[pivot] = values[successor]; values[successor] = temporary;
        for (int left = pivot + 1, right = values.length - 1; left < right; left++, right--) {
            temporary = values[left]; values[left] = values[right]; values[right] = temporary;
        }
        return true;
    }

    private enum Shape { LEFT, RIGHT, BUSHY }
}
