package sqlike.qp;

import sqlike.shared.DataType;
import sqlike.shared.Value;

/**
 * Result of evaluating an expression tree, wrapping the evaluated Value.
 */
public record ExpressionResult(Value value) {
    public ExpressionResult {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null in ExpressionResult");
        }
    }

    public static ExpressionResult of(Value value) {
        return new ExpressionResult(value);
    }

    public DataType type() {
        return value.type();
    }
}
