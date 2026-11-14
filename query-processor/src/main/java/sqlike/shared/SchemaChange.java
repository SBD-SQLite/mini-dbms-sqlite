package sqlike.shared;

import java.util.List;

public record SchemaChange(
        List<SchemaImage> before,
        List<SchemaImage> after
) {
    public SchemaChange {
        before = before == null ? List.of() : List.copyOf(before);
        after = after == null ? List.of() : List.copyOf(after);
    }
}

