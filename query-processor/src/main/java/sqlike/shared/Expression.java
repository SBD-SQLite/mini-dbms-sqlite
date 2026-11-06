package sqlike.shared;

import java.util.List;
import java.util.Optional;

public record Expression(
        ExpressionKind kind,
        Optional<Value> literal,
        Optional<ColumnRef> column,
        Optional<ExpressionOperator> operator,
        List<Expression> operands,
        Optional<SubquerySpec> subquery) {
    public Expression {
        literal = literal == null ? Optional.empty() : literal;
        column = column == null ? Optional.empty() : column;
        operator = operator == null ? Optional.empty() : operator;
        operands = operands == null ? List.of() : List.copyOf(operands);
        subquery = subquery == null ? Optional.empty() : subquery;
    }

    public Expression(
            ExpressionKind kind,
            Optional<Value> literal,
            Optional<ColumnRef> column,
            Optional<ExpressionOperator> operator,
            List<Expression> operands) {
        this(kind, literal, column, operator, operands, Optional.empty());
    }
}
