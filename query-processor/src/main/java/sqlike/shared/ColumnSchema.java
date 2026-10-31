package sqlike.shared;

import java.util.Optional;

public record ColumnSchema(String name, DataType dataType, Optional<Integer> maxLength) {
    public ColumnSchema {
        maxLength = maxLength == null ? Optional.empty() : maxLength;
    }
}
