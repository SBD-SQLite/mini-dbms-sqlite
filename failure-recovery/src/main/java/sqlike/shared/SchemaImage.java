package sqlike.shared;
import java.util.List;
import java.util.Optional;
import sqlike.shared.IndexMetadata;
import sqlike.shared.TableSchema;

public record SchemaImage(String tableName, Optional<TableSchema> schema, List<IndexMetadata> indexes, Optional<String> snapshotId) {
    public SchemaImage {
        indexes = List.copyOf(indexes);
        if (tableName == null || tableName.isBlank() || schema == null || snapshotId == null) throw new IllegalArgumentException("Invalid schema image");
        if (schema.isEmpty() && (!indexes.isEmpty() || snapshotId.isPresent())) throw new IllegalArgumentException("Absent schema must have no indexes or snapshot");
    }
}