package sqlike.shared;

import java.util.Optional;

public record ResultColumn(String name, Optional<ColumnRef> source, DataType dataType) {
    public ResultColumn {
        source = source == null ? Optional.empty() : source;
    }
}
