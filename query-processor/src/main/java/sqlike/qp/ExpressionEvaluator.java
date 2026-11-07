package sqlike.qp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import sqlike.shared.ColumnRef;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.ExpressionOperator;
import sqlike.shared.SubquerySpec;
import sqlike.shared.SubqueryType;
import sqlike.shared.Value;

/**
 * Concrete ExpressionEvaluator implementation matching CLASS DIAGRAM FIX and PRD §13.
 * Evaluates expression trees for arithmetic operations (+, -, *, /), comparisons (=, <>, >, >=, <, <=),
 * logical conditions (AND), and WHERE subqueries (SCALAR, IN, EXISTS, including correlated outer-row scopes
 * and 3-valued SQL NULL logic when a SCALAR subquery returns 0 rows).
 */
public class ExpressionEvaluator implements IExpressionEvaluator {
    private record OuterScope(Row row, Schema schema) { }

    private final IValueComparator comparator;
    private PlanTranslator planTranslator;
    private final ThreadLocal<ExecutionContext> activeContext = new ThreadLocal<>();
    private final ThreadLocal<Deque<OuterScope>> outerScopes = ThreadLocal.withInitial(ArrayDeque::new);

    public ExpressionEvaluator(IValueComparator comparator) {
        if (comparator == null) {
            throw new IllegalArgumentException("IValueComparator cannot be null");
        }
        this.comparator = comparator;
    }

    public IValueComparator getComparator() {
        return comparator;
    }

    public void setPlanTranslator(PlanTranslator planTranslator) {
        this.planTranslator = planTranslator;
    }

    public PlanTranslator getPlanTranslator() {
        return planTranslator;
    }

    public boolean evaluateConditionWithContext(
            Expression expr,
            Row row,
            Schema schema,
            ExecutionContext context) {
        ExecutionContext prevCtx = activeContext.get();
        if (context != null) {
            activeContext.set(context);
        }
        try {
            return evaluateCondition(expr, row, schema);
        } finally {
            if (prevCtx == null) {
                activeContext.remove();
            } else {
                activeContext.set(prevCtx);
            }
        }
    }

    public boolean withOuterScope(Row row, Schema schema, BooleanSupplier action) {
        Deque<OuterScope> stack = outerScopes.get();
        boolean pushed = false;
        if (row != null && schema != null) {
            stack.push(new OuterScope(row, schema));
            pushed = true;
        }
        try {
            return action.getAsBoolean();
        } finally {
            if (pushed) {
                stack.pop();
            }
        }
    }

    @Override
    public ExpressionResult evaluate(Expression expr, Row row, Schema schema) {
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
                var colRef = expr.column().orElseThrow(() ->
                        new IllegalArgumentException("COLUMN expression must contain a ColumnRef"));
                yield ExpressionResult.of(resolveColumnValue(colRef, row, schema));
            }
            case UNARY -> {
                ExpressionOperator op = expr.operator().orElseThrow(() ->
                        new IllegalArgumentException("UNARY expression must contain an operator"));
                if (expr.operands().isEmpty()) {
                    throw new IllegalArgumentException("UNARY expression must contain at least one operand");
                }
                ExpressionResult operandRes = evaluate(expr.operands().get(0), row, schema);
                if (operandRes.isSqlNull()) {
                    yield ExpressionResult.sqlNull();
                }
                if (op == ExpressionOperator.NEGATE) {
                    yield ExpressionResult.of(negateValue(operandRes.value()));
                }
                throw new IllegalArgumentException("Unsupported unary operator: " + op);
            }
            case BINARY -> evaluateBinary(expr, row, schema);
            case SUBQUERY -> evaluateSubqueryValue(expr, row, schema);
        };
    }

    private Value resolveColumnValue(ColumnRef colRef, Row row, Schema schema) {
        if (row != null && schema != null) {
            try {
                int offset = schema.findColumnOffset(colRef);
                return row.getValue(offset);
            } catch (IllegalArgumentException notInCurrent) {
                for (OuterScope outer : outerScopes.get()) {
                    try {
                        int outerOffset = outer.schema().findColumnOffset(colRef);
                        return outer.row().getValue(outerOffset);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                throw notInCurrent;
            }
        }
        for (OuterScope outer : outerScopes.get()) {
            try {
                int outerOffset = outer.schema().findColumnOffset(colRef);
                return outer.row().getValue(outerOffset);
            } catch (IllegalArgumentException ignored) {
            }
        }
        throw new IllegalArgumentException("Row and Schema are required to resolve COLUMN expression");
    }

    @Override
    public boolean evaluateCondition(Expression expr, Row row, Schema schema) {
        if (expr == null) {
            throw new IllegalArgumentException("Condition expression cannot be null");
        }

        if (expr.kind() == ExpressionKind.SUBQUERY) {
            return evaluateSubqueryCondition(expr, row, schema);
        }

        if (expr.kind() != ExpressionKind.BINARY) {
            throw new IllegalArgumentException("Condition must be a binary or subquery expression, found: " + expr.kind());
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

        ExpressionResult leftRes = evaluate(leftExpr, row, schema);
        ExpressionResult rightRes = evaluate(rightExpr, row, schema);
        if (leftRes.isSqlNull() || rightRes.isSqlNull()) {
            // PRD §13: comparison with 0-row SCALAR subquery SQL NULL yields UNKNOWN, which WHERE rejects (false).
            return false;
        }
        return compare(op, leftRes.value(), rightRes.value());
    }

    private ExpressionResult evaluateSubqueryValue(Expression expr, Row row, Schema schema) {
        SubquerySpec sub = expr.subquery().orElseThrow(() ->
                new IllegalArgumentException("SUBQUERY expression must contain a SubquerySpec"));
        if (sub.type() == SubqueryType.EXISTS || sub.type() == SubqueryType.IN) {
            boolean cond = evaluateSubqueryCondition(expr, row, schema);
            return ExpressionResult.of(new Value(
                    DataType.INTEGER,
                    Optional.of(cond ? 1 : 0),
                    Optional.empty(),
                    Optional.empty()));
        }
        if (sub.type() != SubqueryType.SCALAR) {
            throw new IllegalArgumentException("Unsupported subquery type in scalar evaluation: " + sub.type());
        }
        if (!expr.operands().isEmpty()) {
            throw new IllegalArgumentException("SCALAR subquery expects 0 operands");
        }
        Operator subOp = buildSubqueryOperator(sub);
        ExecutionContext ctx = currentExecutionContext();
        Deque<OuterScope> stack = outerScopes.get();
        boolean pushed = false;
        if (row != null && schema != null) {
            stack.push(new OuterScope(row, schema));
            pushed = true;
        }
        try {
            subOp.open(ctx);
            if (subOp.schema().columnCount() != 1) {
                throw new QpException(
                        ErrorCode.SEMANTIC_ERROR,
                        "SCALAR subquery must return exactly 1 column, found: " + subOp.schema().columnCount());
            }
            Optional<RowEnvelope> first = subOp.next();
            if (first.isEmpty()) {
                return ExpressionResult.sqlNull();
            }
            Optional<RowEnvelope> second = subOp.next();
            if (second.isPresent()) {
                throw new QpException(
                        ErrorCode.INVALID_VALUE,
                        "SCALAR subquery returned more than one row");
            }
            return ExpressionResult.of(first.get().row().getValue(0));
        } finally {
            try {
                subOp.close();
            } finally {
                if (pushed) {
                    stack.pop();
                }
            }
        }
    }

    private boolean evaluateSubqueryCondition(Expression expr, Row row, Schema schema) {
        SubquerySpec sub = expr.subquery().orElseThrow(() ->
                new IllegalArgumentException("SUBQUERY expression must contain a SubquerySpec"));
        return switch (sub.type()) {
            case EXISTS -> {
                if (!expr.operands().isEmpty()) {
                    throw new IllegalArgumentException("EXISTS subquery expects 0 operands");
                }
                Operator subOp = buildSubqueryOperator(sub);
                ExecutionContext ctx = currentExecutionContext();
                yield withOuterScope(row, schema, () -> {
                    try {
                        subOp.open(ctx);
                        return subOp.next().isPresent();
                    } finally {
                        subOp.close();
                    }
                });
            }
            case IN -> {
                if (expr.operands().size() != 1) {
                    throw new IllegalArgumentException("IN subquery expects 1 left operand");
                }
                ExpressionResult leftRes = evaluate(expr.operands().get(0), row, schema);
                if (leftRes.isSqlNull()) {
                    yield false;
                }
                Value targetVal = leftRes.value();
                Operator subOp = buildSubqueryOperator(sub);
                ExecutionContext ctx = currentExecutionContext();
                yield withOuterScope(row, schema, () -> {
                    try {
                        subOp.open(ctx);
                        if (subOp.schema().columnCount() != 1) {
                            throw new QpException(
                                    ErrorCode.SEMANTIC_ERROR,
                                    "IN subquery must return exactly 1 column, found: " + subOp.schema().columnCount());
                        }
                        while (true) {
                            Optional<RowEnvelope> next = subOp.next();
                            if (next.isEmpty()) {
                                return false;
                            }
                            Value candidateVal = next.get().row().getValue(0);
                            if (compare(ExpressionOperator.EQ, targetVal, candidateVal)) {
                                return true;
                            }
                        }
                    } finally {
                        subOp.close();
                    }
                });
            }
            case SCALAR -> {
                ExpressionResult res = evaluateSubqueryValue(expr, row, schema);
                if (res.isSqlNull()) {
                    yield false;
                }
                Value v = res.value();
                if (v.type() == DataType.INTEGER) {
                    yield v.integerValue().orElse(0) != 0;
                }
                throw new QpException(ErrorCode.SEMANTIC_ERROR, "SCALAR subquery cannot be used directly as a boolean condition");
            }
        };
    }

    private Operator buildSubqueryOperator(SubquerySpec sub) {
        if (sub.root() == null) {
            throw new IllegalArgumentException("SubquerySpec root PlanNode cannot be null");
        }
        if (planTranslator == null) {
            throw new IllegalStateException("PlanTranslator is required on ExpressionEvaluator to execute subqueries");
        }
        return planTranslator.translateSelect(sub.root());
    }

    private ExecutionContext currentExecutionContext() {
        ExecutionContext ctx = activeContext.get();
        return ctx != null ? ctx : ExecutionContext.of(1L);
    }

    private ExpressionResult evaluateBinary(Expression expr, Row row, Schema schema) {
        if (expr.operands().size() < 2) {
            throw new IllegalArgumentException("Binary expression must contain 2 operands, found: " + expr.operands().size());
        }

        ExpressionOperator op = expr.operator().orElseThrow(() ->
                new IllegalArgumentException("Binary expression must contain an operator"));

        ExpressionResult leftRes = evaluate(expr.operands().get(0), row, schema);
        ExpressionResult rightRes = evaluate(expr.operands().get(1), row, schema);
        if (leftRes.isSqlNull() || rightRes.isSqlNull()) {
            return ExpressionResult.sqlNull();
        }

        Value result = arithmetic(op, leftRes.value(), rightRes.value());
        return ExpressionResult.of(result);
    }

    public Value arithmetic(ExpressionOperator op, Value left, Value right) {
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
            if (!Double.isFinite(res)) {
                throw new QpException(ErrorCode.INVALID_VALUE, "Non-finite floating point result");
            }
            return new Value(DataType.FLOAT, Optional.empty(), Optional.of(res), Optional.empty());
        } else {
            int l = left.integerValue().orElse(0);
            int r = right.integerValue().orElse(0);

            int res = switch (op) {
                case ADD -> Math.addExact(l, r);
                case SUBTRACT -> Math.subtractExact(l, r);
                case MULTIPLY -> Math.multiplyExact(l, r);
                case DIVIDE -> {
                    if (r == 0) throw new ArithmeticException("Division by zero");
                    if (l == Integer.MIN_VALUE && r == -1) throw new ArithmeticException("Integer overflow");
                    yield l / r;
                }
                default -> throw new IllegalArgumentException("Unsupported arithmetic operator: " + op);
            };
            return new Value(DataType.INTEGER, Optional.of(res), Optional.empty(), Optional.empty());
        }
    }

    public Value arithmetic(BinaryOp op, Value left, Value right) {
        if (op == null) throw new IllegalArgumentException("BinaryOp cannot be null");
        return arithmetic(op.toExpressionOperator(), left, right);
    }

    public boolean compare(ExpressionOperator op, Value left, Value right) {
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
        if (op == null) throw new IllegalArgumentException("BinaryOp cannot be null");
        return compare(op.toExpressionOperator(), left, right);
    }

    private Value negateValue(Value val) {
        if (val.type() == DataType.INTEGER) {
            return new Value(DataType.INTEGER, Optional.of(Math.negateExact(val.integerValue().orElse(0))), Optional.empty(), Optional.empty());
        } else if (val.type() == DataType.FLOAT) {
            return new Value(DataType.FLOAT, Optional.empty(), Optional.of(-val.floatValue().orElse(0.0)), Optional.empty());
        }
        throw new IllegalArgumentException("Cannot negate non-numeric Value of type: " + val.type());
    }

    private boolean isNumeric(DataType type) {
        return type == DataType.INTEGER || type == DataType.FLOAT;
    }
}
