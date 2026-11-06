package sqlike.ccm;

import sqlike.shared.ResourceId;

/**
 * One granted read or write operation recorded in an execution schedule (CC-09).
 */
public record ScheduleOperation(
        long txId,
        ResourceId resource,
        boolean write) {
}
