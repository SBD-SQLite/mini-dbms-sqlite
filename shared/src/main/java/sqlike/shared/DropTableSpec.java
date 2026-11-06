package sqlike.shared;

public record DropTableSpec(
        String tableName,
        DropBehavior behavior
) {
}
