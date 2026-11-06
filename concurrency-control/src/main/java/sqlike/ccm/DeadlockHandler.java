package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds cycles in the lock protocol's wait-for graph.
 * The CCM facade handles aborting the transaction chosen as the victim.
 */
public class DeadlockHandler {

    private final Map<Long, Set<Long>> waitFor = new HashMap<>();

    public synchronized List<Long> detectCycle() {
        Set<Long> finished = new HashSet<>();
        Map<Long, Integer> visiting = new HashMap<>();
        List<Long> path = new ArrayList<>();
        for (long txId : new TreeSet<>(waitFor.keySet())) {
            List<Long> cycle = visit(txId, finished, visiting, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    /**
     * Chooses the newest transaction by txId. The shared contract uses txId as its timestamp.
     */
    public long chooseVictim(List<Long> cycle) {
        Objects.requireNonNull(cycle, "cycle");
        if (cycle.isEmpty()) {
            throw new IllegalArgumentException("A deadlock victim requires a non-empty cycle");
        }
        return Collections.max(cycle);
    }

    public synchronized Map<Long, Set<Long>> getWaitForGraph() {
        Map<Long, Set<Long>> snapshot = new HashMap<>();
        waitFor.forEach((txId, blockers) -> snapshot.put(txId, Set.copyOf(blockers)));
        return Map.copyOf(snapshot);
    }

    synchronized void updateWaitFor(Map<Long, Set<Long>> graph) {
        waitFor.clear();
        graph.forEach((txId, blockers) -> {
            if (!blockers.isEmpty()) {
                waitFor.put(txId, Set.copyOf(blockers));
            }
        });
    }

    synchronized void reset() {
        waitFor.clear();
    }

    private List<Long> visit(long txId, Set<Long> finished, Map<Long, Integer> visiting,
                             List<Long> path) {
        Integer start = visiting.get(txId);
        if (start != null) {
            return List.copyOf(path.subList(start, path.size()));
        }
        if (finished.contains(txId)) {
            return List.of();
        }
        visiting.put(txId, path.size());
        path.add(txId);
        for (long blocker : new TreeSet<>(waitFor.getOrDefault(txId, Set.of()))) {
            List<Long> cycle = visit(blocker, finished, visiting, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        visiting.remove(txId);
        finished.add(txId);
        return List.of();
    }
}
