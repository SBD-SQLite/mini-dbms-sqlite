package sqlike.shared;

import java.util.List;
import java.util.Optional;

public record MutationSpec(
        String tableName,
        List<Value> insertValues,
        Optional<ColumnRef> updateColumn,
        Optional<Expression> updateExpression) {
    public MutationSpec {
        insertValues = insertValues == null ? List.of() : List.copyOf(insertValues);
        updateColumn = updateColumn == null ? Optional.empty() : updateColumn;
        updateExpression = updateExpression == null ? Optional.empty() : updateExpression;
    }
}
