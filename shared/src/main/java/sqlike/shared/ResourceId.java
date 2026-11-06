package sqlike.shared;

import java.util.Optional;

public record ResourceId(
        ResourceScope scope,
        String tableName,
        Optional<RID> rid
) {
}
