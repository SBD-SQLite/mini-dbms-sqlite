package sqlike.ccm;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deadlock checks for the lock-based protocol.
 *
 * <p>Cycle detection is still a milestone 1 mock and returns an empty list.
 * The CCM facade handles aborting the transaction chosen as the victim.
 */
public class DeadlockHandler {

    private final Map<Long, Set<Long>> waitFor = new HashMap<>();

    public List<Long> detectCycle() {
        // TODO(CC2): find cycles in the wait-for graph.
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

    void reset() {
        waitFor.clear();
    }
}
