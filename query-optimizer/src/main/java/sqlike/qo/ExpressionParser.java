package sqlike.qo;

import java.util.List;
import java.util.Optional;
import sqlike.shared.*;

public class ExpressionParser {
    public Expression parseExpression(TokenStream ts) {
        if (ts.isAtEnd()) throw ts.unexpected("expected expression");
        Expression expression = parseAnd(ts);
        if (ts.peek().isKeyword(Keyword.OR))
            throw QoErrors.unsupported("OR expressions are not part of the agreed contract", ts.peek().offset());
        return expression;
    }

    private Expression parseAnd(TokenStream ts) {
        Expression expression = parseCompare(ts);
        while (ts.matchKeyword(Keyword.AND))
            expression = binary(ExpressionOperator.AND, expression, parseCompare(ts));
        return expression;
    }

    private Expression parseCompare(TokenStream ts) {
        Expression expression = parseAdditive(ts);
        ExpressionOperator operator = comparisonOperator(ts.peek());
        if (operator == null) return expression;
        ts.next();
        expression = binary(operator, expression, parseAdditive(ts));
        if (comparisonOperator(ts.peek()) != null)
            throw QoErrors.syntax("chained comparisons are not supported", ts.peek().offset());
        return expression;
    }

    private Expression parseAdditive(TokenStream ts) {
        Expression expression = parseMultiplicative(ts);
        while (ts.peek().isSymbol("+") || ts.peek().isSymbol("-")) {
            String operator = ts.next().text();
            expression = binary(operator.equals("+") ? ExpressionOperator.ADD : ExpressionOperator.SUBTRACT,
                    expression, parseMultiplicative(ts));
        }
        return expression;
    }

    private Expression parseMultiplicative(TokenStream ts) {
        Expression expression = parseUnary(ts);
        while (ts.peek().isSymbol("*") || ts.peek().isSymbol("/")) {
            String operator = ts.next().text();
            expression = binary(operator.equals("*") ? ExpressionOperator.MULTIPLY : ExpressionOperator.DIVIDE,
                    expression, parseUnary(ts));
        }
        return expression;
    }

    private Expression parseUnary(TokenStream ts) {
        if (ts.matchSymbol("+")) return parseUnary(ts);
        if (ts.matchSymbol("-")) {
            Token number = ts.peek();
            if (number.type() == TokenType.INTEGER) {
                ts.next();
                try {
                    long value = -Long.parseLong(number.text());
                    if (value < Integer.MIN_VALUE)
                        throw QoErrors.invalidValue("integer literal exceeds 32 bit", number.offset());
                    return literal(Literals.integer((int) value));
                } catch (NumberFormatException exception) {
                    throw QoErrors.invalidValue("invalid integer literal", number.offset());
                }
            }
            if (number.type() == TokenType.FLOAT) {
                ts.next();
                return literal(floatValue(-parseFiniteFloat(number)));
            }
            return unary(ExpressionOperator.NEGATE, parseUnary(ts));
        }
        return parseAtom(ts);
    }

    private Expression parseAtom(TokenStream ts) {
        Token token = ts.peek();
        if (ts.matchSymbol("(")) {
            Expression expression = parseAnd(ts);
            ts.expectSymbol(")");
            return expression;
        }
        if (token.type() == TokenType.INTEGER) {
            ts.next();
            try { return literal(Literals.integer(Integer.parseInt(token.text()))); }
            catch (NumberFormatException exception) {
                throw QoErrors.invalidValue("integer literal exceeds 32 bit", token.offset());
            }
        }
        if (token.type() == TokenType.FLOAT) {
            ts.next();
            return literal(floatValue(parseFiniteFloat(token)));
        }
        if (token.type() == TokenType.STRING) {
            ts.next();
            Value value = new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of(token.text()));
            return literal(value);
        }
        if (token.type() == TokenType.KEYWORD && (token.isKeyword(Keyword.NULL) || token.isKeyword(Keyword.NOT)))
            throw QoErrors.unsupported("NULL and NOT expressions are not part of the agreed contract", token.offset());
        if (token.type() == TokenType.IDENTIFIER) {
            String first = ts.next().text();
            ColumnRef column = ts.matchSymbol(".")
                    ? ColumnRef.of(first, ts.expectIdentifier("column name").text())
                    : ColumnRef.of(null, first);
            return new Expression(ExpressionKind.COLUMN, Optional.empty(), Optional.of(column), Optional.empty(), List.of());
        }
        throw QoErrors.syntax("expected literal, column reference, or parenthesized expression", token.offset());
    }

    private double parseFiniteFloat(Token token) {
        try {
            double value = Double.parseDouble(token.text());
            if (!Double.isFinite(value)) throw QoErrors.invalidValue("float literal must be finite", token.offset());
            return value;
        } catch (NumberFormatException exception) {
            throw QoErrors.invalidValue("invalid float literal", token.offset());
        }
    }

    private Value floatValue(double value) {
        return new Value(DataType.FLOAT, Optional.empty(), Optional.of(value), Optional.empty());
    }

    private Expression literal(Value value) {
        return new Expression(ExpressionKind.LITERAL, Optional.of(value), Optional.empty(), Optional.empty(), List.of());
    }

    private Expression unary(ExpressionOperator operator, Expression operand) {
        return new Expression(ExpressionKind.UNARY, Optional.empty(), Optional.empty(), Optional.of(operator), List.of(operand));
    }

    private Expression binary(ExpressionOperator operator, Expression left, Expression right) {
        return new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(), Optional.of(operator), List.of(left, right));
    }

    private ExpressionOperator comparisonOperator(Token token) {
        if (token.type() != TokenType.SYMBOL) return null;
        return switch (token.text()) {
            case "=" -> ExpressionOperator.EQ;
            case "<>", "!=" -> ExpressionOperator.NE;
            case ">" -> ExpressionOperator.GT;
            case ">=" -> ExpressionOperator.GE;
            case "<" -> ExpressionOperator.LT;
            case "<=" -> ExpressionOperator.LE;
            default -> null;
        };
    }
}
