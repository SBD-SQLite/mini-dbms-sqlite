package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import sqlike.shared.ResourceId;
import sqlike.shared.ResourceScope;

/**
 * Verifies conflict serializability using a Precedence Graph and Kahn's Topological Sort (CC-09).
 */
public class SerializabilityVerifier {

    public Map<Long, Set<Long>> buildPrecedenceGraph(List<ScheduleOperation> schedule) {
        Map<Long, Set<Long>> graph = new LinkedHashMap<>();
        for (ScheduleOperation op : schedule) {
            graph.computeIfAbsent(op.txId(), k -> new LinkedHashSet<>());
        }

        int n = schedule.size();
        for (int i = 0; i < n; i++) {
            ScheduleOperation earlier = schedule.get(i);
            for (int j = i + 1; j < n; j++) {
                ScheduleOperation later = schedule.get(j);
                if (conflicts(earlier, later)) {
                    graph.get(earlier.txId()).add(later.txId());
                }
            }
        }
        return graph;
    }

    public boolean isConflictSerializable(List<ScheduleOperation> schedule) {
        Map<Long, Set<Long>> graph = buildPrecedenceGraph(schedule);
        return computeTopologicalOrder(graph).size() == graph.size();
    }

    public List<Long> serialOrder(List<ScheduleOperation> schedule) {
        Map<Long, Set<Long>> graph = buildPrecedenceGraph(schedule);
        List<Long> order = computeTopologicalOrder(graph);
        if (order.size() != graph.size()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(order);
    }

    private List<Long> computeTopologicalOrder(Map<Long, Set<Long>> graph) {
        Map<Long, Integer> inDegree = new HashMap<>();
        for (Long txId : graph.keySet()) {
            inDegree.put(txId, 0);
        }
        for (Set<Long> neighbors : graph.values()) {
            for (Long target : neighbors) {
                inDegree.merge(target, 1, Integer::sum);
            }
        }

        PriorityQueue<Long> ready = new PriorityQueue<>();
        for (Map.Entry<Long, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<Long> order = new ArrayList<>(graph.size());
        while (!ready.isEmpty()) {
            long current = ready.poll();
            order.add(current);
            for (Long neighbor : graph.getOrDefault(current, Collections.emptySet())) {
                int updated = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, updated);
                if (updated == 0) {
                    ready.add(neighbor);
                }
            }
        }
        return order;
    }

    private boolean conflicts(ScheduleOperation op1, ScheduleOperation op2) {
        if (op1.txId() == op2.txId()) {
            return false;
        }
        if (!op1.write() && !op2.write()) {
            return false;
        }
        return resourcesOverlap(op1.resource(), op2.resource());
    }

    private boolean resourcesOverlap(ResourceId r1, ResourceId r2) {
        if (r1.equals(r2)) {
            return true;
        }
        if (!r1.tableName().equals(r2.tableName())) {
            return false;
        }
        return r1.scope() == ResourceScope.TABLE || r2.scope() == ResourceScope.TABLE;
    }
}
