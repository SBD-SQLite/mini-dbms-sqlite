package sqlike.qo;

import java.util.ArrayList;
import java.util.Collections;
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
        if (plan.kind() == PlanNodeKind.LIMIT || QO5Plans.hasSubquery(plan)) return List.of(plan);
        if (JoinSearchSpace.isRegion(plan)) return enumerateRegion(plan);
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
        JoinSearchSpace space = JoinSearchSpace.from(original).orElseThrow();
        Set<PlanNode> result = new LinkedHashSet<>();
        result.add(original);
        // Diversify outer relations before spending the remaining budget on permutations.
        List<Integer> order = new ArrayList<>(space.originalOrder());
        for (int i = 0; i < space.size() && result.size() < maxCandidates; i++) {
            addShapes(space, order, result);
            Collections.rotate(order, -1);
        }
        Collections.reverse(order);
        addShapes(space, order, result);
        if (space.size() <= PERMUTATION_LIMIT) {
            int[] permutation = new int[space.size()];
            for (int i = 0; i < permutation.length; i++) permutation[i] = i;
            do {
                List<Integer> permuted = new ArrayList<>();
                for (int index : permutation) permuted.add(index);
                addShapes(space, permuted, result);
            } while (result.size() < maxCandidates && nextPermutation(permutation));
        }
        return List.copyOf(result);
    }

    private void addShapes(JoinSearchSpace space, List<Integer> order, Set<PlanNode> result) {
        for (JoinSearchSpace.Shape shape : JoinSearchSpace.Shape.values()) {
            if (result.size() >= maxCandidates) return;
            result.add(space.decode(order, shape));
        }
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

}
