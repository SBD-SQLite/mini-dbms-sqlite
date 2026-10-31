package sqlike.qp;

import sqlike.shared.ExpressionOperator;

/**
 * Binary operator enumeration matching CLASS DIAGRAM FIX for ExpressionEvaluator arithmetic and comparison.
 */
public enum BinaryOp {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    NEGATE,
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
    AND;

    public static BinaryOp fromExpressionOperator(ExpressionOperator op) {
        if (op == null) return null;
        return switch (op) {
            case ADD -> ADD;
            case SUBTRACT -> SUBTRACT;
            case MULTIPLY -> MULTIPLY;
            case DIVIDE -> DIVIDE;
            case NEGATE -> NEGATE;
            case EQ -> EQ;
            case NE -> NE;
            case GT -> GT;
            case GE -> GE;
            case LT -> LT;
            case LE -> LE;
            case AND -> AND;
        };
    }

    public ExpressionOperator toExpressionOperator() {
        return switch (this) {
            case ADD -> ExpressionOperator.ADD;
            case SUBTRACT -> ExpressionOperator.SUBTRACT;
            case MULTIPLY -> ExpressionOperator.MULTIPLY;
            case DIVIDE -> ExpressionOperator.DIVIDE;
            case NEGATE -> ExpressionOperator.NEGATE;
            case EQ -> ExpressionOperator.EQ;
            case NE -> ExpressionOperator.NE;
            case GT -> ExpressionOperator.GT;
            case GE -> ExpressionOperator.GE;
            case LT -> ExpressionOperator.LT;
            case LE -> ExpressionOperator.LE;
            case AND -> ExpressionOperator.AND;
        };
    }
}
