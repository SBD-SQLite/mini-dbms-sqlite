package sqlike.shared;

import java.util.Optional;

public record ColumnRef(Optional<String> tableName, String columnName) {
    public ColumnRef {
        tableName = tableName == null ? Optional.empty() : tableName;
        if (columnName == null || columnName.isEmpty()) throw new IllegalArgumentException("columnName required");
    }
    public static ColumnRef of(String table, String column) { return new ColumnRef(Optional.ofNullable(table), column); }
}
