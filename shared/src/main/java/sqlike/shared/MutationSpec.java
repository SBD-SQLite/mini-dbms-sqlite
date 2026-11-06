package sqlike.shared;

import java.util.List;
import java.util.Optional;

public record MutationSpec(
        String tableName,
        List<Value> insertValues,
        Optional<ColumnRef> updateColumn,
        Optional<Expression> updateExpression
) {
}
