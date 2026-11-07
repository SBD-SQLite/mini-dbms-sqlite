package sqlike.shared;

import java.util.List;

public record CreateTableSpec(
        String tableName,
        List<ColumnSchema> columns,
        List<String> primaryKey,
        List<ForeignKeySpec> foreignKeys
) {
    public CreateTableSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
        primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
    }
}

