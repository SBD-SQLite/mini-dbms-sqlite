package sqlike.shared;

import java.util.Optional;

public record ScanSpec(String tableName, Optional<String> indexName, Optional<IndexCondition> condition) {
    public ScanSpec {
        indexName = indexName == null ? Optional.empty() : indexName;
        condition = condition == null ? Optional.empty() : condition;
    }
}
