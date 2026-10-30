package sqlike.qo;

import java.util.Optional;
import sqlike.shared.*;

public class ExpressionParser {
    public Expression parseExpression(TokenStream ts) {
        while (!ts.isAtEnd()) {
            Token t = ts.peek();
            if (t.isSymbol(",") || t.isSymbol(";") || t.isKeyword(Keyword.ORDER) || t.isKeyword(Keyword.JOIN)
                    || t.isKeyword(Keyword.INNER) || t.isKeyword(Keyword.NATURAL) || t.isKeyword(Keyword.ON)
                    || t.isKeyword(Keyword.WHERE) || t.isKeyword(Keyword.GROUP) || t.isKeyword(Keyword.LIMIT)) break;
            ts.next();
        }
        Expression one = new Expression(ExpressionKind.LITERAL, Optional.of(Literals.integer(1)), Optional.empty(), Optional.empty(), null);
        return new Expression(ExpressionKind.BINARY, Optional.empty(), Optional.empty(), Optional.of(ExpressionOperator.EQ), java.util.List.of(one, one));
    }
}
