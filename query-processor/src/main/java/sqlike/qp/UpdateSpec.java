package sqlike.qp;

import java.util.Optional;
import sqlike.shared.ColumnRef;
import sqlike.shared.Expression;
import sqlike.shared.MutationSpec;

/**
 * Specification for update executor carrying update target and expression, matching CLASS DIAGRAM FIX.
 */
public record UpdateSpec(String tableName, Optional<ColumnRef> updateColumn, Optional<Expression> updateExpression) {
    public UpdateSpec {
        updateColumn = updateColumn == null ? Optional.empty() : updateColumn;
        updateExpression = updateExpression == null ? Optional.empty() : updateExpression;
    }

    public static UpdateSpec fromMutationSpec(MutationSpec mutation) {
        if (mutation == null) {
            throw new IllegalArgumentException("MutationSpec cannot be null");
        }
        return new UpdateSpec(mutation.tableName(), mutation.updateColumn(), mutation.updateExpression());
    }
}
