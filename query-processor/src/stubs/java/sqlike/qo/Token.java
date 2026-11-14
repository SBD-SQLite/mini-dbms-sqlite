package sqlike.qo;

public record Token(TokenType type, String text, int offset, int length, Keyword keyword) {
    public boolean isKeyword(Keyword k) { return type == TokenType.KEYWORD && keyword == k; }
    public boolean isSymbol(String s) { return type == TokenType.SYMBOL && text.equals(s); }
    public boolean isEof() { return type == TokenType.EOF; }
}
