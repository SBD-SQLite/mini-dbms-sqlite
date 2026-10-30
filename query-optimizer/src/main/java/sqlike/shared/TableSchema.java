package sqlike.shared;

import java.util.List;

public record TableSchema(long tableId, String tableName, List<ColumnSchema> columns) {
    public TableSchema { columns = columns == null ? List.of() : List.copyOf(columns); }
}
