package sqlike.qp.stub;

import java.util.*;
import sqlike.shared.*;
import sqlike.qp.*;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.*;

public final class StubRuntime {
    private StubRuntime() { }

    /** STUB ONLY: a functional evaluator over the real QO expression tree. */
    public static final class StubExpressionEvaluator implements IExpressionEvaluator {
        public ExpressionResult evaluate(Expr expr, Row row, Schema schema) {
            return evaluate(expr.value(), row, schema);
        }

        public boolean evaluateCondition(Expr expr, Row row, Schema schema) {
            return evaluateCondition(expr.value(), row, schema);
        }

        @Override
        public ExpressionResult evaluate(Expression tree, Row row, Schema schema) {
            if (tree.kind() == ExpressionKind.LITERAL) return value(tree.literal().orElseThrow());
            if (tree.kind() == ExpressionKind.COLUMN) return value(row.values().get(schema.offset(tree.column().orElseThrow())));
            ExpressionOperator op = tree.operator().orElseThrow();
            if (op == ExpressionOperator.NEGATE) {
                Object n = payload(evaluate(tree.operands().get(0), row, schema).value());
                return value(n instanceof Integer i ? sqlike.qo.Literals.integer(Math.negateExact(i)) : number(-((Number) n).doubleValue()));
            }
            Value leftVal = evaluate(tree.operands().get(0), row, schema).value();
            Value rightVal = evaluate(tree.operands().get(1), row, schema).value();
            Object a = payload(leftVal), b = payload(rightVal);
            if (Set.of(ExpressionOperator.ADD, ExpressionOperator.SUBTRACT, ExpressionOperator.MULTIPLY, ExpressionOperator.DIVIDE).contains(op)) {
                if (!(a instanceof Number x) || !(b instanceof Number y)) throw failure("Arithmetic requires numbers");
                if (op == ExpressionOperator.DIVIDE && y.doubleValue() == 0) throw failure("Division by zero");
                if (a instanceof Integer i && b instanceof Integer j) {
                    int n = switch (op) {
                        case ADD -> Math.addExact(i, j); case SUBTRACT -> Math.subtractExact(i, j);
                        case MULTIPLY -> Math.multiplyExact(i, j);
                        default -> { if (i == Integer.MIN_VALUE && j == -1) throw failure("Integer overflow"); yield i / j; }
                    };
                    return value(sqlike.qo.Literals.integer(n));
                }
                double n = switch (op) {
                    case ADD -> x.doubleValue() + y.doubleValue(); case SUBTRACT -> x.doubleValue() - y.doubleValue();
                    case MULTIPLY -> x.doubleValue() * y.doubleValue(); default -> x.doubleValue() / y.doubleValue();
                };
                return value(number(n));
            }
            throw failure("Unsupported operator in evaluate: " + op);
        }

        private ExpressionResult value(Value value) { return ExpressionResult.of(value); }

        @Override
        public boolean evaluateCondition(Expression tree, Row row, Schema schema) {
            ExpressionOperator op = tree.operator().orElseThrow();
            if (op == ExpressionOperator.AND) {
                return evaluateCondition(tree.operands().get(0), row, schema) && evaluateCondition(tree.operands().get(1), row, schema);
            }
            Value leftVal = evaluate(tree.operands().get(0), row, schema).value();
            Value rightVal = evaluate(tree.operands().get(1), row, schema).value();
            Object a = payload(leftVal), b = payload(rightVal);
            int comparison;
            if (a instanceof Number x && b instanceof Number y) comparison = Double.compare(x.doubleValue(), y.doubleValue());
            else if (a instanceof String x && b instanceof String y) comparison = x.compareTo(y);
            else throw failure("Incompatible comparison types");
            return switch (op) {
                case EQ -> comparison == 0; case NE -> comparison != 0; case GT -> comparison > 0;
                case GE -> comparison >= 0; case LT -> comparison < 0; case LE -> comparison <= 0;
                default -> throw failure("Unsupported comparison: " + op);
            };
        }
    }
}
