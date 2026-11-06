package sqlike.shared;

import java.util.Optional;

public record ScanSpec(
        String tableName,
        Optional<String> indexName,
        Optional<IndexCondition> condition,
        Optional<String> alias
) {
    public ScanSpec(String tableName, Optional<String> indexName, Optional<IndexCondition> condition) {
        this(tableName, indexName, condition, Optional.empty());
    }
}
