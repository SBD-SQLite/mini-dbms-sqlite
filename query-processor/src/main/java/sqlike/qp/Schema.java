package sqlike.qp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sqlike.qp.port.QpContracts;
import sqlike.shared.ColumnRef;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;

/**
 * Relation or operator schema defining ordered columns and supporting single-table
 * and multi-relation (JOIN) column name resolution.
 */
public final class Schema {
    private final List<ColumnSchema> columns;
    private final Optional<String> tableName;
    private final List<Set<String>> columnTableNames;

    public Schema(List<ColumnSchema> columns, Optional<String> tableName) {
        this(columns, tableName, null);
    }

    public Schema(TableSchema tableSchema) {
        this(
                tableSchema != null ? tableSchema.columns() : List.of(),
                tableSchema != null ? Optional.ofNullable(tableSchema.tableName()) : Optional.empty(),
                null);
    }

    Schema(List<ColumnSchema> columns, Optional<String> tableName, List<Set<String>> explicitTableNames) {
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.tableName = tableName == null ? Optional.empty() : tableName;
        List<Set<String>> owners = new ArrayList<>(this.columns.size());
        for (int i = 0; i < this.columns.size(); i++) {
            Set<String> set = new LinkedHashSet<>();
            if (explicitTableNames != null && i < explicitTableNames.size() && explicitTableNames.get(i) != null) {
                for (String t : explicitTableNames.get(i)) {
                    if (t != null && !t.isBlank()) {
                        set.add(t.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            this.tableName.ifPresent(t -> {
                if (!t.isBlank()) {
                    set.add(t.trim().toLowerCase(Locale.ROOT));
                }
            });
            String colName = this.columns.get(i).name();
            if (colName != null) {
                int dot = colName.indexOf('.');
                if (dot > 0) {
                    set.add(colName.substring(0, dot).trim().toLowerCase(Locale.ROOT));
                }
            }
            owners.add(Collections.unmodifiableSet(set));
        }
        this.columnTableNames = Collections.unmodifiableList(owners);
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

    static Schema withColumnTables(
            List<ColumnSchema> columns,
            Optional<String> tableName,
            List<Set<String>> columnTableNames) {
        return new Schema(columns, tableName, columnTableNames);
    }

    Set<String> columnTablesAt(int index) {
        return columnTableNames.get(index);
    }

    public List<ColumnSchema> columns() {
        return columns;
    }

    public Optional<String> tableName() {
        return tableName;
    }

    public int findColumnOffset(ColumnRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("ColumnRef cannot be null");
        }
        return findColumnOffset(ref.tableName().orElse(null), ref.columnName());
    }

    public int findColumnOffset(String targetTable, String targetColumn) {
        if (targetColumn == null || targetColumn.isBlank()) {
            throw new IllegalArgumentException("Column name cannot be null or blank");
        }
        String normCol = targetColumn.trim();
        String normTable = (targetTable == null || targetTable.isBlank())
                ? null
                : targetTable.trim().toLowerCase(Locale.ROOT);

        if (normTable != null) {
            for (int i = 0; i < columns.size(); i++) {
                ColumnSchema col = columns.get(i);
                if (matchesColumnName(col.name(), normTable, normCol)) {
                    Set<String> owners = columnTableNames.get(i);
                    if (owners.contains(normTable)
                            || (tableName.isPresent() && tableName.get().equalsIgnoreCase(normTable))) {
                        return i;
                    }
                }
            }
            boolean anyOwnerKnown = tableName.isPresent()
                    || columnTableNames.stream().anyMatch(s -> !s.isEmpty());
            if (!anyOwnerKnown) {
                for (int i = 0; i < columns.size(); i++) {
                    if (matchesColumnName(columns.get(i).name(), normTable, normCol)) {
                        return i;
                    }
                }
            }
            throw new IllegalArgumentException(
                    "Column not found in schema: " + targetTable + "." + targetColumn);
        }

        for (int i = 0; i < columns.size(); i++) {
            ColumnSchema col = columns.get(i);
            if (matchesColumnName(col.name(), null, normCol)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Column not found in schema: " + targetColumn);
    }

    private static boolean matchesColumnName(String schemaColName, String targetTable, String targetCol) {
        if (schemaColName == null) {
            return false;
        }
        if (schemaColName.equalsIgnoreCase(targetCol)) {
            return true;
        }
        if (targetTable != null && schemaColName.equalsIgnoreCase(targetTable + "." + targetCol)) {
            return true;
        }
        int dot = schemaColName.indexOf('.');
        if (dot > 0) {
            String prefix = schemaColName.substring(0, dot);
            String bare = schemaColName.substring(dot + 1);
            if (bare.equalsIgnoreCase(targetCol)
                    && (targetTable == null || prefix.equalsIgnoreCase(targetTable))) {
                return true;
            }
        }
        return false;
    }

    public ColumnSchema getColumn(int index) {
        return columns.get(index);
    }

    public List<ColumnSchema> getColumns() {
        return columns;
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
        var copy = new ArrayList<Value>();
        for (int i = 0; i < row.values().size(); i++) {
            copy.add(coerce(i, row.values().get(i)));
        }
        return new Row(copy);
    }

    public Value coerce(int offset, Value input) {
        ColumnSchema column = columns.get(offset);
        Object payload = QpContracts.payload(input);
        if (column.dataType() == DataType.FLOAT && input.type() == DataType.INTEGER) {
            input = QpContracts.number(((Integer) payload).doubleValue());
        } else if ((column.dataType() == DataType.CHAR || column.dataType() == DataType.VARCHAR)
                && payload instanceof String) {
            input = new Value(column.dataType(), Optional.empty(), Optional.empty(), Optional.of((String) payload));
        }
        if (input.type() != column.dataType()) {
            throw new IllegalStateException("Type mismatch: " + column.name());
        }
        if (payload instanceof String text && column.maxLength().isPresent()
                && text.getBytes(StandardCharsets.UTF_8).length > column.maxLength().get()) {
            throw new IllegalStateException("Text too long: " + column.name());
        }
        return input;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Schema other)) {
            return false;
        }
        return Objects.equals(columns, other.columns) && Objects.equals(tableName, other.tableName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns, tableName);
    }

    @Override
    public String toString() {
        return "Schema[columns=" + columns + ", tableName=" + tableName + "]";
    }
}
