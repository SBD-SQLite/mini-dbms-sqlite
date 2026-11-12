package sqlike.shared;

public record DropTableSpec(
        String tableName,
        DropBehavior behavior
) {
    public DropTableSpec {
        behavior = behavior == null ? DropBehavior.RESTRICT : behavior;
    }
}

