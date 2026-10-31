package sqlike.qp;

import java.util.List;
import java.util.Optional;
import sqlike.shared.ColumnRef;
import sqlike.shared.ColumnSchema;
import sqlike.shared.TableSchema;

/**
 * Relation or operator schema defining ordered columns and supporting name resolution.
 */
public record Schema(List<ColumnSchema> columns, Optional<String> tableName) {
    public Schema {
        columns = columns == null ? List.of() : List.copyOf(columns);
        tableName = tableName == null ? Optional.empty() : tableName;
    }

    public static Schema of(List<ColumnSchema> columns) {
        return new Schema(columns, Optional.empty());
    }

    public static Schema of(String tableName, List<ColumnSchema> columns) {
        return new Schema(columns, Optional.ofNullable(tableName));
    }

    public static Schema fromTableSchema(TableSchema tableSchema) {
        return new Schema(tableSchema.columns(), Optional.ofNullable(tableSchema.tableName()));
    }

    public int findColumnOffset(ColumnRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("ColumnRef cannot be null");
        }
        return findColumnOffset(ref.tableName().orElse(null), ref.columnName());
    }

    public int findColumnOffset(String targetTable, String targetColumn) {
        for (int i = 0; i < columns.size(); i++) {
            ColumnSchema col = columns.get(i);
            if (col.name().equalsIgnoreCase(targetColumn)) {
                if (targetTable == null || tableName.isEmpty() || tableName.get().equalsIgnoreCase(targetTable)) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Column not found in schema: " + (targetTable != null ? targetTable + "." : "") + targetColumn);
    }

    public ColumnSchema getColumn(int index) {
        return columns.get(index);
    }

    public int columnCount() {
        return columns.size();
    }
}
