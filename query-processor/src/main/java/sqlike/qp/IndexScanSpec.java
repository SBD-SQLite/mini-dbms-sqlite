package sqlike.qp;

import java.util.Optional;
import sqlike.shared.IndexCondition;
import sqlike.shared.ScanSpec;

/**
 * Specification for index scan operator matching CLASS DIAGRAM FIX.
 */
public record IndexScanSpec(String tableName, String indexName, Optional<IndexCondition> condition) {
    public IndexScanSpec {
        condition = condition == null ? Optional.empty() : condition;
    }

    public IndexScanSpec(ScanSpec scanSpec) {
        this(scanSpec != null ? scanSpec.tableName() : "",
             scanSpec != null ? scanSpec.indexName().orElse("") : "",
             scanSpec != null ? scanSpec.condition() : Optional.empty());
    }

    public static IndexScanSpec fromScanSpec(ScanSpec scanSpec) {
        if (scanSpec == null) {
            throw new IllegalArgumentException("ScanSpec cannot be null");
        }
        return new IndexScanSpec(
                scanSpec.tableName(),
                scanSpec.indexName().orElse(""),
                scanSpec.condition());
    }
}
