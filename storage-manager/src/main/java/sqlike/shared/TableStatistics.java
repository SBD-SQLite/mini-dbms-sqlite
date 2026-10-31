package sqlike.shared;

import java.util.HashMap;
import java.util.Map;

public class TableStatistics {
    long recordCount;
    long blockCount;
    double averageRecordSize;
    int blockingFactor;
    Map<String,Long> distinctCounts;

    public TableStatistics(long recordCount, long blockCount, double averageRecordSize,int blockingFactor){
        this.recordCount = recordCount;
        this.blockCount = blockCount;
        this.averageRecordSize = averageRecordSize;
        this.blockingFactor = blockingFactor;
        this.distinctCounts = new HashMap<>();
    }

    public long getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(long recordCount) {
        this.recordCount = recordCount;
    }

    public long getBlockCount() {
        return blockCount;
    }

    public void setBlockCount(long blockCount) {
        this.blockCount = blockCount;
    }

    public double getAverageRecordSize() {
        return averageRecordSize;
    }

    public void setAverageRecordSize(double averageRecordSize) {
        this.averageRecordSize = averageRecordSize;
    }

    public int getBlockingFactor() {
        return blockingFactor;
    }

    public void setBlockingFactor(int blockingFactor) {
        this.blockingFactor = blockingFactor;
    }

    public Map<String, Long> getDistinctCounts() {
        return distinctCounts;
    }

    public void setDistinctCounts(Map<String, Long> distinctCounts) {
        this.distinctCounts = distinctCounts;
    }
}
