package sqlike.shared;

import java.util.List;
import java.util.Optional;

public record SchemaImage(
        String tableName,
        Optional<TableSchema> schema,
        List<IndexMetadata> indexes,
        Optional<String> snapshotId
) {
    public SchemaImage {
        schema = schema == null ? Optional.empty() : schema;
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        snapshotId = snapshotId == null ? Optional.empty() : snapshotId;
    }
}

