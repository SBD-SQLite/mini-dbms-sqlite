package sqlike.shared;

import java.util.Optional;

public record ScanSpec(
        String tableName,
        Optional<String> indexName,
        Optional<IndexCondition> condition,
        Optional<String> alias) {
    public ScanSpec {
        indexName = indexName == null ? Optional.empty() : indexName;
        condition = condition == null ? Optional.empty() : condition;
        alias = alias == null ? Optional.empty() : alias;
    }

    public ScanSpec(
            String tableName,
            Optional<String> indexName,
            Optional<IndexCondition> condition) {
        this(tableName, indexName, condition, Optional.empty());
    }
}
