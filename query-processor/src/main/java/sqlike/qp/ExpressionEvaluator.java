package sqlike.qp;

import java.util.Optional;
import sqlike.shared.DataType;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.ExpressionOperator;
import sqlike.shared.Value;

/**
 * Concrete ExpressionEvaluator implementation matching CLASS DIAGRAM FIX.
 * Evaluates expression trees for arithmetic operations (+, -, *, /), comparisons (=, <>, >, >=, <, <=),
 * and logical conditions (AND) against in-flight rows and schemas.
 */
public class ExpressionEvaluator implements IExpressionEvaluator {
    private final IValueComparator comparator;

    public ExpressionEvaluator(IValueComparator comparator) {
        // TODO(M1-MOCK): Store value comparator dependency
        if (comparator == null) {
            throw new IllegalArgumentException("IValueComparator cannot be null");
        }
        this.comparator = comparator;
    }

    public IValueComparator getComparator() {
        return comparator;
    }

    @Override
    public ExpressionResult evaluate(Expression expr, Row row, Schema schema) {
        // TODO(M1-MOCK): Evaluate expression against row and schema
        if (expr == null) {
            throw new IllegalArgumentException("Expression cannot be null");
        }

        return switch (expr.kind()) {
            case LITERAL -> {
                Value lit = expr.literal().orElseThrow(() ->
                        new IllegalArgumentException("LITERAL expression must contain a Value"));
                yield ExpressionResult.of(lit);
            }
            case COLUMN -> {
                if (row == null || schema == null) {
                    throw new IllegalArgumentException("Row and Schema are required to resolve COLUMN expression");
                }
                var colRef = expr.column().orElseThrow(() ->
                        new IllegalArgumentException("COLUMN expression must contain a ColumnRef"));
                int offset = schema.findColumnOffset(colRef);
                Value val = row.getValue(offset);
                yield ExpressionResult.of(val);
            }
            case UNARY -> {
                ExpressionOperator op = expr.operator().orElseThrow(() ->
                        new IllegalArgumentException("UNARY expression must contain an operator"));
                if (expr.operands().isEmpty()) {
                    throw new IllegalArgumentException("UNARY expression must contain at least one operand");
                }
                Value operandVal = evaluate(expr.operands().get(0), row, schema).value();
                if (op == ExpressionOperator.NEGATE) {
                    yield ExpressionResult.of(negateValue(operandVal));
                }
                throw new IllegalArgumentException("Unsupported unary operator: " + op);
            }
            case BINARY -> evaluateBinary(expr, row, schema);
        };
    }

    @Override
    public boolean evaluateCondition(Expression expr, Row row, Schema schema) {
        // TODO(M1-MOCK): Evaluate boolean condition
        if (expr == null) {
            throw new IllegalArgumentException("Condition expression cannot be null");
        }

        if (expr.kind() != ExpressionKind.BINARY) {
            throw new IllegalArgumentException("Condition must be a binary expression, found: " + expr.kind());
        }

        ExpressionOperator op = expr.operator().orElseThrow(() ->
                new IllegalArgumentException("Binary condition must contain an operator"));

        if (expr.operands().size() < 2) {
            throw new IllegalArgumentException("Binary condition must contain 2 operands, found: " + expr.operands().size());
        }

        Expression leftExpr = expr.operands().get(0);
        Expression rightExpr = expr.operands().get(1);

        if (op == ExpressionOperator.AND) {
            return evaluateCondition(leftExpr, row, schema) && evaluateCondition(rightExpr, row, schema);
        }

        Value leftVal = evaluate(leftExpr, row, schema).value();
        Value rightVal = evaluate(rightExpr, row, schema).value();
        return compare(op, leftVal, rightVal);
    }

    private ExpressionResult evaluateBinary(Expression expr, Row row, Schema schema) {
        // TODO(M1-MOCK): Evaluate binary arithmetic expression
        if (expr.operands().size() < 2) {
            throw new IllegalArgumentException("Binary expression must contain 2 operands, found: " + expr.operands().size());
        }

        ExpressionOperator op = expr.operator().orElseThrow(() ->
                new IllegalArgumentException("Binary expression must contain an operator"));

        Value leftVal = evaluate(expr.operands().get(0), row, schema).value();
        Value rightVal = evaluate(expr.operands().get(1), row, schema).value();

        Value result = arithmetic(op, leftVal, rightVal);
        return ExpressionResult.of(result);
    }

    public Value arithmetic(ExpressionOperator op, Value left, Value right) {
        // TODO(M1-MOCK): Perform arithmetic calculation (+, -, *, /)
        if (left == null || right == null) {
            throw new IllegalArgumentException("Operands cannot be null for arithmetic operation");
        }

        if (!isNumeric(left.type()) || !isNumeric(right.type())) {
            throw new IllegalArgumentException("Arithmetic operands must be numeric, got: " + left.type() + " and " + right.type());
        }

        boolean useFloat = left.type() == DataType.FLOAT || right.type() == DataType.FLOAT;

        if (useFloat) {
            double l = left.type() == DataType.INTEGER ? left.integerValue().orElse(0) : left.floatValue().orElse(0.0);
            double r = right.type() == DataType.INTEGER ? right.integerValue().orElse(0) : right.floatValue().orElse(0.0);

            double res = switch (op) {
                case ADD -> l + r;
                case SUBTRACT -> l - r;
                case MULTIPLY -> l * r;
                case DIVIDE -> {
                    if (r == 0.0) throw new ArithmeticException("Division by zero");
                    yield l / r;
                }
                default -> throw new IllegalArgumentException("Unsupported arithmetic operator: " + op);
            };
            return new Value(DataType.FLOAT, Optional.empty(), Optional.of(res), Optional.empty());
        } else {
            int l = left.integerValue().orElse(0);
            int r = right.integerValue().orElse(0);

            int res = switch (op) {
                case ADD -> l + r;
                case SUBTRACT -> l - r;
                case MULTIPLY -> l * r;
                case DIVIDE -> {
                    if (r == 0) throw new ArithmeticException("Division by zero");
                    yield l / r;
                }
                default -> throw new IllegalArgumentException("Unsupported arithmetic operator: " + op);
            };
            return new Value(DataType.INTEGER, Optional.of(res), Optional.empty(), Optional.empty());
        }
    }

    public Value arithmetic(BinaryOp op, Value left, Value right) {
        // TODO(M1-MOCK): BinaryOp overload matching CLASS DIAGRAM FIX
        if (op == null) throw new IllegalArgumentException("BinaryOp cannot be null");
        return arithmetic(op.toExpressionOperator(), left, right);
    }

    public boolean compare(ExpressionOperator op, Value left, Value right) {
        // TODO(M1-MOCK): Compare two Values using comparator
        int cmp = comparator.compare(left, right);
        return switch (op) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            default -> throw new IllegalArgumentException("Unsupported comparison operator: " + op);
        };
    }

    public boolean compare(BinaryOp op, Value left, Value right) {
        // TODO(M1-MOCK): BinaryOp overload matching CLASS DIAGRAM FIX
        if (op == null) throw new IllegalArgumentException("BinaryOp cannot be null");
        return compare(op.toExpressionOperator(), left, right);
    }

    private Value negateValue(Value val) {
        if (val.type() == DataType.INTEGER) {
            return new Value(DataType.INTEGER, Optional.of(-val.integerValue().orElse(0)), Optional.empty(), Optional.empty());
        } else if (val.type() == DataType.FLOAT) {
            return new Value(DataType.FLOAT, Optional.empty(), Optional.of(-val.floatValue().orElse(0.0)), Optional.empty());
        }
        throw new IllegalArgumentException("Cannot negate non-numeric Value of type: " + val.type());
    }

    private boolean isNumeric(DataType type) {
        return type == DataType.INTEGER || type == DataType.FLOAT;
    }
}
