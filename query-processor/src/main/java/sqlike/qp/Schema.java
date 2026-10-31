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

    public Schema(TableSchema tableSchema) {
        this(tableSchema != null ? tableSchema.columns() : List.of(),
             tableSchema != null ? Optional.ofNullable(tableSchema.tableName()) : Optional.empty());
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

    public int offset(ColumnRef ref) {
        return findColumnOffset(ref);
    }

    public Row validate(Row row) {
        if (row == null || row.values().size() != columns.size()) {
            throw new IllegalStateException("Wrong number of INSERT/UPDATE values");
        }
        var copy = new java.util.ArrayList<sqlike.shared.Value>();
        for (int i = 0; i < row.values().size(); i++) {
            copy.add(coerce(i, row.values().get(i)));
        }
        return new Row(copy);
    }

    public sqlike.shared.Value coerce(int offset, sqlike.shared.Value input) {
        sqlike.shared.ColumnSchema column = columns.get(offset);
        Object payload = sqlike.qp.port.QpContracts.payload(input);
        if (column.dataType() == sqlike.shared.DataType.FLOAT && input.type() == sqlike.shared.DataType.INTEGER) {
            input = sqlike.qp.port.QpContracts.number(((Integer) payload).doubleValue());
        } else if ((column.dataType() == sqlike.shared.DataType.CHAR || column.dataType() == sqlike.shared.DataType.VARCHAR) && payload instanceof String) {
            input = new sqlike.shared.Value(column.dataType(), Optional.empty(), Optional.empty(), Optional.of((String) payload));
        }
        if (input.type() != column.dataType()) {
            throw new IllegalStateException("Type mismatch: " + column.name());
        }
        if (payload instanceof String text && column.maxLength().isPresent()
                && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > column.maxLength().get()) {
            throw new IllegalStateException("Text too long: " + column.name());
        }
        return input;
    }
}

