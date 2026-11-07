package sqlike.shared;

import java.util.List;

public record TableSchema(
        String tableId,
        String tableName,
        List<ColumnSchema> columns,
        List<String> primaryKey,
        List<ForeignKeySpec> foreignKeys) {
    public TableSchema {
        columns = columns == null ? List.of() : List.copyOf(columns);
        primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        foreignKeys = foreignKeys == null ? List.of() : List.copyOf(foreignKeys);
    }

    public TableSchema(String tableId, String tableName, List<ColumnSchema> columns) {
        this(tableId, tableName, columns, List.of(), List.of());
    }
}
