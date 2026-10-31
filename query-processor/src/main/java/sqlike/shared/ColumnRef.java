package sqlike.shared;

import java.util.Optional;

public record ColumnRef(Optional<String> tableName, String columnName) {
    public ColumnRef {
        tableName = tableName == null ? Optional.empty() : tableName;
    }
}
