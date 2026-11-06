package sqlike.shared;

import java.util.List;

public record ForeignKeySpec(
        List<String> columnNames,
        String referencedTable,
        List<String> referencedColumns
) {
}
