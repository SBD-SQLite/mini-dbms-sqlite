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

        MutationSpec mutation = new MutationSpec(table, List.of(), Optional.of(Refs.column(table, column)), Optional.of(update));
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
        ts.expectKeyword(Keyword.CREATE);
        ts.expectKeyword(Keyword.TABLE);
        String table = identifier(ts, "table name");
        ts.expectSymbol("(");
        List<ColumnSchema> columns = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        List<ForeignKeySpec> foreignKeys = new ArrayList<>();
        do {
            if (ts.matchKeyword(Keyword.PRIMARY)) {
                ts.expectKeyword(Keyword.KEY);
                if (!primaryKey.isEmpty()) throw ts.unexpected("multiple PRIMARY KEY declarations");
                primaryKey.addAll(columnList(ts));
            } else if (ts.matchKeyword(Keyword.FOREIGN)) {
                ts.expectKeyword(Keyword.KEY);
                List<String> local = columnList(ts);
                ts.expectKeyword(Keyword.REFERENCES);
                String referencedTable = identifier(ts, "referenced table name");
                List<String> referenced = columnList(ts);
                foreignKeys.add(new ForeignKeySpec(local, referencedTable, referenced));
            } else {
                String name = identifier(ts, "column name");
                Token typeToken = ts.expectIdentifier("column type");
                DataType type;
                try { type = DataType.valueOf(typeToken.text().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException exception) {
                    throw QoErrors.syntax("expected INTEGER, FLOAT, CHAR(n), or VARCHAR(n)", typeToken.offset());
                }
                // The grammar accepts an optional "(n)" after any type; whether the type needs, forbids or
                // allows the length (and a duplicate column name) is a definition rule that DdlValidator reports.
                Optional<Integer> length = Optional.empty();
                if (ts.matchSymbol("(")) {
                    boolean negative = ts.matchSymbol("-");
                    Token size = ts.peek();
                    if (size.type() != TokenType.INTEGER) throw ts.unexpected("expected a character length");
                    ts.next();
                    try {
                        int value = Integer.parseInt(size.text());
                        length = Optional.of(negative ? -value : value);
                    } catch (NumberFormatException exception) {
                        throw QoErrors.syntax("character length must fit in a 32 bit integer", size.offset());
                    }
                    ts.expectSymbol(")");
                }
                columns.add(new ColumnSchema(name, type, length));
                if (ts.matchKeyword(Keyword.PRIMARY)) {
                    ts.expectKeyword(Keyword.KEY);
                    if (!primaryKey.isEmpty()) throw ts.unexpected("multiple PRIMARY KEY declarations");
                    primaryKey.add(name);
                }
            }
        } while (ts.matchSymbol(","));
        ts.expectSymbol(")");
        ts.expectEndOfStatement();
        return query(StatementType.CREATE_TABLE, PlanNodes.createTable(new CreateTableSpec(
                table, List.copyOf(columns), List.copyOf(primaryKey), List.copyOf(foreignKeys))));
    }

    public ParsedQuery parseDropTable(TokenStream ts) {
        ts.expectKeyword(Keyword.DROP);
        ts.expectKeyword(Keyword.TABLE);
        String table = identifier(ts, "table name");
        DropBehavior behavior = DropBehavior.RESTRICT;
        if (ts.matchKeyword(Keyword.CASCADE)) behavior = DropBehavior.CASCADE;
        else ts.matchKeyword(Keyword.RESTRICT);
        ts.expectEndOfStatement();
        return query(StatementType.DROP_TABLE, PlanNodes.dropTable(new DropTableSpec(table, behavior)));
    }

    private List<String> columnList(TokenStream ts) {
        ts.expectSymbol("(");
        List<String> columns = new ArrayList<>();
        columns.add(identifier(ts, "column name"));
        while (ts.matchSymbol(",")) columns.add(identifier(ts, "column name"));
        ts.expectSymbol(")");
        return List.copyOf(columns);
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
