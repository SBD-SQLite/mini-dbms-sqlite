package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Thread-safe recorder for operations granted by CCM (CC-09, CC-10).
 */
public class ScheduleRecorder {

    private final List<ScheduleOperation> operations = new ArrayList<>();
    private final Set<Long> committedTxIds = new HashSet<>();

    public synchronized void record(ScheduleOperation op) {
        if (op != null) {
            operations.add(op);
        }
    }

    public synchronized void markCommitted(long txId) {
        committedTxIds.add(txId);
    }

    /**
     * Returns operations belonging to committed transactions in execution order.
     */
    public synchronized List<ScheduleOperation> committedSchedule() {
        List<ScheduleOperation> committed = new ArrayList<>();
        for (ScheduleOperation op : operations) {
            if (committedTxIds.contains(op.txId())) {
                committed.add(op);
            }
        }
        return Collections.unmodifiableList(committed);
    }

    public synchronized List<ScheduleOperation> allOperations() {
        return List.copyOf(operations);
    }

    public synchronized void reset() {
        operations.clear();
        committedTxIds.clear();
    }
}
