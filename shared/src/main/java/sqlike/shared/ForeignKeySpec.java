package sqlike.shared;

import java.util.List;

public record ForeignKeySpec(
        List<String> columnNames,
        String referencedTable,
        List<String> referencedColumns
) {
    public ForeignKeySpec {
        columnNames = columnNames == null ? List.of() : List.copyOf(columnNames);
        referencedColumns = referencedColumns == null ? List.of() : List.copyOf(referencedColumns);
    }
}

