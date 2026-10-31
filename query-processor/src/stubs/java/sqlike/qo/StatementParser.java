package sqlike.qo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import sqlike.shared.*;

public class StatementParser {
    private final ExpressionParser expressionParser = new ExpressionParser();

    public ParsedQuery parseTransaction(TokenStream ts) {
        Token token = ts.next();
        TransactionAction action;
        if (token.isKeyword(Keyword.BEGIN)) {
            action = TransactionAction.BEGIN;
            ts.matchKeyword(Keyword.TRANSACTION);
        } else if (token.isKeyword(Keyword.COMMIT)) {
            action = TransactionAction.COMMIT;
        } else if (token.isKeyword(Keyword.ABORT)) {
            action = TransactionAction.ABORT;
        } else {
            throw QoErrors.syntax("expected BEGIN, COMMIT, or ABORT", token.offset());
        }
        ts.expectEndOfStatement();
        return new ParsedQuery(StatementType.valueOf(action.name()), Optional.empty(),
                Optional.of(new TransactionCommand(action)));
    }

    public ParsedQuery parseInsert(TokenStream ts) {
        ts.expectKeyword(Keyword.INSERT);
        ts.expectKeyword(Keyword.INTO);
        String table = identifier(ts, "table name");
        ts.expectKeyword(Keyword.VALUES);
        ts.expectSymbol("(");
        List<Value> values = new ArrayList<>();
        values.add(parseInsertValue(ts));
        while (ts.matchSymbol(",")) values.add(parseInsertValue(ts));
        ts.expectSymbol(")");
        ts.expectEndOfStatement();

        MutationSpec mutation = new MutationSpec(table, values, Optional.empty(), Optional.empty());
        return query(StatementType.INSERT, PlanNodes.insert(mutation));
    }

    public ParsedQuery parseUpdate(TokenStream ts) {
        ts.expectKeyword(Keyword.UPDATE);
        String table = identifier(ts, "table name");
        ts.expectKeyword(Keyword.SET);
        String column = identifier(ts, "column name");
        ts.expectSymbol("=");
        Expression update = expressionParser.parseExpression(ts);
        Expression predicate = ts.matchKeyword(Keyword.WHERE) ? expressionParser.parseExpression(ts) : null;
        ts.expectEndOfStatement();

        MutationSpec mutation = new MutationSpec(table, List.of(), Optional.of(ColumnRef.of(table, column)), Optional.of(update));
        return query(StatementType.UPDATE, PlanNodes.update(filteredScan(table, predicate), mutation));
    }

    public ParsedQuery parseDelete(TokenStream ts) {
        ts.expectKeyword(Keyword.DELETE);
        ts.expectKeyword(Keyword.FROM);
        String table = identifier(ts, "table name");
        Expression predicate = ts.matchKeyword(Keyword.WHERE) ? expressionParser.parseExpression(ts) : null;
        ts.expectEndOfStatement();

        MutationSpec mutation = new MutationSpec(table, List.of(), Optional.empty(), Optional.empty());
        return query(StatementType.DELETE, PlanNodes.delete(filteredScan(table, predicate), mutation));
    }

    public ParsedQuery parseCreateIndex(TokenStream ts) {
        ts.expectKeyword(Keyword.CREATE);
        ts.expectKeyword(Keyword.INDEX);
        String index = identifier(ts, "index name");
        ts.expectKeyword(Keyword.ON);
        String table = identifier(ts, "table name");
        ts.expectSymbol("(");
        String column = identifier(ts, "column name");
        ts.expectSymbol(")");

        IndexType indexType = IndexType.HASH;
        if (ts.matchKeyword(Keyword.USING)) {
            Token type = ts.expectIdentifier("index type (HASH or BPLUS_TREE)");
            try { indexType = IndexType.valueOf(type.text().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception) {
                throw QoErrors.syntax("index type must be HASH or BPLUS_TREE", type.offset());
            }
        }
        ts.expectEndOfStatement();
        return query(StatementType.CREATE_INDEX,
                PlanNodes.createIndex(new IndexMetadata(index, table, column, indexType)));
    }

    public ParsedQuery parseCreateTable(TokenStream ts) {
        throw QoErrors.unsupported("CREATE TABLE is not part of the agreed contract", ts.peek().offset());
    }

    public ParsedQuery parseDropTable(TokenStream ts) {
        throw QoErrors.unsupported("DROP TABLE is not part of the agreed contract", ts.peek().offset());
    }

    private Value parseInsertValue(TokenStream ts) {
        Expression expression = expressionParser.parseExpression(ts);
        if (expression.kind() != ExpressionKind.LITERAL || expression.literal().isEmpty())
            throw QoErrors.syntax("INSERT values must be literals", ts.peek().offset());
        return expression.literal().get();
    }

    private PlanNode filteredScan(String table, Expression predicate) {
        PlanNode scan = PlanNodes.scan(table);
        return predicate == null ? scan : PlanNodes.filter(scan, predicate);
    }

    private String identifier(TokenStream ts, String what) {
        return ts.expectIdentifier(what).text();
    }

    private ParsedQuery query(StatementType type, PlanNode root) {
        PlanNodes.validate(root);
        return new ParsedQuery(type, Optional.of(root), Optional.empty());
    }
}
