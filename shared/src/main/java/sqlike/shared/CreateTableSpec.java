package sqlike.shared;

import java.util.List;

public record CreateTableSpec(
        String tableName,
        List<ColumnSchema> columns,
        List<String> primaryKey,
        List<ForeignKeySpec> foreignKeys
) {
}
