package sqlike.qo;

import sqlike.shared.*;

public class QueryParser {
    private final SelectParser selectParser;
    private final StatementParser statementParser;

    public QueryParser(SelectParser selectParser, StatementParser statementParser) {
        this.selectParser = selectParser;
        this.statementParser = statementParser;
    }

    public ParsedQuery parse(TokenStream ts, String sql) {
        Token t = ts.peek();
        if (t.isEof()) throw QoErrors.syntax("empty statement", t.offset());
        if (t.isKeyword(Keyword.SELECT)) return selectParser.parseSelect(ts);
        if (t.isKeyword(Keyword.INSERT)) return statementParser.parseInsert(ts);
        if (t.isKeyword(Keyword.UPDATE)) return statementParser.parseUpdate(ts);
        if (t.isKeyword(Keyword.DELETE)) return statementParser.parseDelete(ts);
        if (t.isKeyword(Keyword.BEGIN) || t.isKeyword(Keyword.COMMIT) || t.isKeyword(Keyword.ABORT))
            return statementParser.parseTransaction(ts);
        if (t.isKeyword(Keyword.CREATE)) {
            if (ts.peek(1).isKeyword(Keyword.INDEX)) return statementParser.parseCreateIndex(ts);
            if (ts.peek(1).isKeyword(Keyword.TABLE)) return statementParser.parseCreateTable(ts);
            throw QoErrors.syntax("expected INDEX or TABLE after CREATE", ts.peek(1).offset());
        }
        if (t.isKeyword(Keyword.DROP)) return statementParser.parseDropTable(ts);
        throw QoErrors.syntax("unknown statement start '" + t.text() + "'", t.offset());
    }
}
