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

    public UpdateSpec(sqlike.qp.port.QpContracts.TableId tableId, ColumnRef column, sqlike.qp.port.QpContracts.Expr value) {
        this(tableId != null ? tableId.value() : "",
             Optional.ofNullable(column),
             value != null ? Optional.ofNullable(value.value()) : Optional.empty());
    }

    public sqlike.qp.port.QpContracts.TableId tableId() {
        return new sqlike.qp.port.QpContracts.TableId(tableName);
    }

    public ColumnRef column() {
        return updateColumn.orElseThrow(() -> new IllegalStateException("UpdateSpec missing column"));
    }

    public sqlike.qp.port.QpContracts.Expr value() {
        return new sqlike.qp.port.QpContracts.Expr(updateExpression.orElseThrow(() -> new IllegalStateException("UpdateSpec missing expression")));
    }

    public static UpdateSpec fromMutationSpec(MutationSpec mutation) {
        if (mutation == null) {
            throw new IllegalArgumentException("MutationSpec cannot be null");
        }
        return new UpdateSpec(mutation.tableName(), mutation.updateColumn(), mutation.updateExpression());
    }
}
