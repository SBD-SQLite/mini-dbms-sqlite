package sqlike.qo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sqlike.shared.*;


public class SelectParser {
    private final ExpressionParser expressionParser;
    public SelectParser(ExpressionParser expressionParser) { this.expressionParser = expressionParser; }

    public ParsedQuery parseSelect(TokenStream ts) {
        ts.expectKeyword(Keyword.SELECT);
        if (ts.peek().isKeyword(Keyword.DISTINCT))
            throw QoErrors.unsupported("DISTINCT is not part of the agreed contract", ts.peek().offset());
        List<ColumnRef> columns = parseSelectList(ts);
        ts.expectKeyword(Keyword.FROM);
        PlanNode node = parseFrom(ts);
        if (ts.matchKeyword(Keyword.WHERE)) node = PlanNodes.filter(node, expressionParser.parseExpression(ts));
        if (ts.peek().isKeyword(Keyword.ORDER)) node = PlanNodes.sort(node, parseOrderBy(ts));
        if (ts.peek().isKeyword(Keyword.GROUP) || ts.peek().isKeyword(Keyword.HAVING))
            throw QoErrors.unsupported("GROUP BY/HAVING are not part of the agreed contract", ts.peek().offset());
        ts.expectEndOfStatement();
        node = PlanNodes.project(node, columns);
        return new ParsedQuery(StatementType.SELECT, Optional.of(node), Optional.empty());
    }

    private List<ColumnRef> parseSelectList(TokenStream ts) {
        List<ColumnRef> cols = new ArrayList<>();
        do {
            if (ts.matchSymbol("*")) {
                cols.add(new ColumnRef(Optional.empty(), "*"));
                continue;
            }
            Token first = ts.expectIdentifier("column name or '*'");
            if (ts.peek().isSymbol("("))
                throw QoErrors.unsupported("functions/aggregates are not part of the agreed contract", first.offset());
            if (ts.matchSymbol(".")) {
                if (ts.matchSymbol("*")) cols.add(ColumnRef.of(first.text(), "*"));
                else cols.add(ColumnRef.of(first.text(), ts.expectIdentifier("column name").text()));
            } else {
                cols.add(new ColumnRef(Optional.empty(), first.text()));
            }
            if (ts.peek().isKeyword(Keyword.AS))
                throw QoErrors.unsupported("column alias (AS) is not part of the agreed contract", ts.peek().offset());
        } while (ts.matchSymbol(","));
        return cols;
    }

    private PlanNode parseFrom(TokenStream ts) {
        PlanNode left = parseTableRef(ts);
        while (true) {
            Token t = ts.peek();
            if (ts.matchSymbol(",")) {
                PlanNode right = parseTableRef(ts);
                left = PlanNodes.join(left, right, new JoinSpec(JoinType.CARTESIAN, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of()));
            } else if (t.isKeyword(Keyword.NATURAL)) {
                ts.next();
                ts.expectKeyword(Keyword.JOIN);
                PlanNode right = parseTableRef(ts);
                // commonColumns stays empty sampai Binder resolves it from the catalog
                left = PlanNodes.join(left, right, new JoinSpec(JoinType.NATURAL, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of()));
            } else if (t.isKeyword(Keyword.JOIN) || t.isKeyword(Keyword.INNER)) {
                ts.matchKeyword(Keyword.INNER);
                ts.expectKeyword(Keyword.JOIN);
                PlanNode right = parseTableRef(ts);
                ts.expectKeyword(Keyword.ON);
                Expression cond = expressionParser.parseExpression(ts);
                left = PlanNodes.join(left, right, new JoinSpec(JoinType.THETA, Optional.of(cond), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of()));
            } else {
                return left;
            }
        }
    }

    private PlanNode parseTableRef(TokenStream ts) {
        if (ts.peek().isSymbol("("))
            throw QoErrors.unsupported("subquery is not part of the agreed contract", ts.peek().offset());
        Token name = ts.expectIdentifier("table name");
        Token p = ts.peek();
        if (p.isKeyword(Keyword.AS) || p.type() == TokenType.IDENTIFIER)
            throw QoErrors.unsupported("table alias is not part of the agreed contract", p.offset());
        return PlanNodes.scan(name.text());
    }

    private SortSpec parseOrderBy(TokenStream ts) {
        ts.expectKeyword(Keyword.ORDER);
        ts.expectKeyword(Keyword.BY);
        Token first = ts.expectIdentifier("column name");
        ColumnRef col = ts.matchSymbol(".")
                ? ColumnRef.of(first.text(), ts.expectIdentifier("column name").text())
                : new ColumnRef(Optional.empty(), first.text());
        SortDirection dir = SortDirection.ASC;
        if (ts.matchKeyword(Keyword.DESC)) dir = SortDirection.DESC;
        else ts.matchKeyword(Keyword.ASC);
        if (ts.peek().isSymbol(","))
            throw QoErrors.unsupported("ORDER BY supports a single column only", ts.peek().offset());
        return new SortSpec(col, dir);
    }
}
