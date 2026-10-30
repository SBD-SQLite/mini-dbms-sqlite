package sqlike.qo;
import java.util.List;
import java.util.Optional;
import sqlike.shared.*;


public class StatementParser {
    private static final Expression ZERO = new Expression(ExpressionKind.LITERAL, Optional.of(Literals.integer(0)), Optional.empty(), Optional.empty(), null);

    public ParsedQuery parseTransaction(TokenStream ts) {
        Token t = ts.next();
        TransactionAction a = t.isKeyword(Keyword.BEGIN) ? TransactionAction.BEGIN
                : t.isKeyword(Keyword.COMMIT) ? TransactionAction.COMMIT : TransactionAction.ABORT;
        if (a == TransactionAction.BEGIN) ts.matchKeyword(Keyword.TRANSACTION);
        ts.expectEndOfStatement();
        StatementType st = StatementType.valueOf(a.name());
        return new ParsedQuery(st, Optional.empty(), Optional.of(new TransactionCommand(a)));
    }

    public ParsedQuery parseInsert(TokenStream ts) {
        drain(ts); 
        return new ParsedQuery(StatementType.INSERT, Optional.of(PlanNodes.insert(
                new MutationSpec("MOCK", List.of(Literals.integer(0)), Optional.empty(), Optional.empty()))), Optional.empty());
    }

    public ParsedQuery parseUpdate(TokenStream ts) {
        drain(ts); 
        return new ParsedQuery(StatementType.UPDATE, Optional.of(PlanNodes.update(PlanNodes.scan("MOCK"),
                new MutationSpec("MOCK", List.of(), Optional.of(ColumnRef.of("MOCK", "MOCK")), Optional.of(ZERO)))), Optional.empty());
    }

    public ParsedQuery parseDelete(TokenStream ts) {
        drain(ts); 
        return new ParsedQuery(StatementType.DELETE, Optional.of(PlanNodes.delete(PlanNodes.scan("MOCK"),
                new MutationSpec("MOCK", List.of(), Optional.empty(), Optional.empty()))), Optional.empty());
    }

    public ParsedQuery parseCreateIndex(TokenStream ts) {
        drain(ts);
        return new ParsedQuery(StatementType.CREATE_INDEX, Optional.of(PlanNodes.createIndex(
                new IndexMetadata("MOCK", "MOCK", "MOCK", IndexType.HASH))), Optional.empty());
    }

    public ParsedQuery parseCreateTable(TokenStream ts) {
        throw QoErrors.unsupported("CREATE TABLE is not part of the agreed contract", ts.peek().offset());
    }
    public ParsedQuery parseDropTable(TokenStream ts) {
        throw QoErrors.unsupported("DROP TABLE is not part of the agreed contract", ts.peek().offset());
    }
    private static void drain(TokenStream ts) { while (!ts.isAtEnd()) ts.next(); }
}
