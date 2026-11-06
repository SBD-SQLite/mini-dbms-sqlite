package sqlike.shared;

import java.util.Map;

public record TableStatistics(long recordCount, long blockCount, double averageRecordSize, int blockingFactor, Map<String, Long> distinctCounts) {
    public TableStatistics { distinctCounts = distinctCounts == null ? Map.of() : Map.copyOf(distinctCounts); }
}
