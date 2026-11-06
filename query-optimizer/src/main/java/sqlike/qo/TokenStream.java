package sqlike.qo;

import java.util.List;
import sqlike.shared.QoException;

/** cursor over the tokens produced by {@link Lexer}, selalu diakhiri with one EOF token */
public final class TokenStream {
    private final List<Token> tokens;
    private int pos;

    public TokenStream(List<Token> tokens) {
        if (tokens == null || tokens.isEmpty() || !tokens.get(tokens.size() - 1).isEof())
            throw new IllegalArgumentException("token list must end with EOF");
        this.tokens = List.copyOf(tokens);
    }

    public Token peek() { return peek(0); }
    public Token peek(int ahead) { return tokens.get(Math.min(pos + ahead, tokens.size() - 1)); }
    public Token next() { Token t = peek(); if (!t.isEof()) pos++; return t; }
    public boolean isAtEnd() { return peek().isEof(); }
    public int position() { return pos; }
    public void reset(int position) { this.pos = position; }

    public boolean matchKeyword(Keyword k) { if (peek().isKeyword(k)) { pos++; return true; } return false; }
    public boolean matchSymbol(String s) { if (peek().isSymbol(s)) { pos++; return true; } return false; }

    public Token expectKeyword(Keyword k) {
        if (!peek().isKeyword(k)) throw unexpected("expected " + k);
        return next();
    }
    public Token expectSymbol(String s) {
        if (!peek().isSymbol(s)) throw unexpected("expected '" + s + "'");
        return next();
    }
    public Token expectIdentifier(String what) {
        if (peek().type() != TokenType.IDENTIFIER) throw unexpected("expected " + what);
        return next();
    }
    public void expectEndOfStatement() {
        matchSymbol(";");
        Token t = peek();
        if (t.isKeyword(Keyword.LIMIT)) throw QoErrors.unsupported("LIMIT is not part of the agreed contract", t.offset());
        if (!t.isEof()) throw unexpected("unexpected token at end of statement");
    }

    public QoException unexpected(String message) {
        Token t = peek();
        String found = t.isEof() ? "end of input" : "'" + t.text() + "'";
        return QoErrors.syntax(message + ", found " + found, t.offset());
    }
}
