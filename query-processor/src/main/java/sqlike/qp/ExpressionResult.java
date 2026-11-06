package sqlike.qp;

import java.util.Optional;
import sqlike.shared.DataType;
import sqlike.shared.Value;

/**
 * Result of evaluating an expression tree, wrapping the evaluated Value
 * (or representing an internal SQL NULL for 0-row SCALAR subqueries per PRD §13).
 */
public record ExpressionResult(Value value) {
    private static final Value SQL_NULL_SENTINEL =
            new Value(DataType.INTEGER, Optional.empty(), Optional.empty(), Optional.empty());

    public ExpressionResult {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null in ExpressionResult");
        }
    }

    public static ExpressionResult of(Value value) {
        return new ExpressionResult(value);
    }

    public static ExpressionResult sqlNull() {
        return new ExpressionResult(SQL_NULL_SENTINEL);
    }

    public boolean isSqlNull() {
        return value == SQL_NULL_SENTINEL;
    }

    public DataType type() {
        return value.type();
    }
}
