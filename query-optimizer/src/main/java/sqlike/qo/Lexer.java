package sqlike.qo;

import java.util.ArrayList;
import java.util.List;

/** turns SQL text into a {@link TokenStream}, stateless */
public class Lexer {
    private static final long MAX_INT_MAGNITUDE = 2147483648L; //2^31, allows -2147483648 setelah negation

    public TokenStream tokenize(String sql) {
        if (sql == null) throw QoErrors.syntax("SQL text is null", 0);
        List<Token> out = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') { 
                while (i < n && sql.charAt(i) != '\n') i++;
                continue;
            }
            int start = i;
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                String word = sql.substring(start, i);
                var kw = Keyword.lookup(word);
                out.add(kw.isPresent()
                        ? new Token(TokenType.KEYWORD, word, start, i - start, kw.get())
                        : new Token(TokenType.IDENTIFIER, word, start, i - start, null));
            } else if (Character.isDigit(c)) {
                while (i < n && Character.isDigit(sql.charAt(i))) i++;
                boolean isFloat = false;
                if (i + 1 < n && sql.charAt(i) == '.' && Character.isDigit(sql.charAt(i + 1))) {
                    isFloat = true;
                    i++;
                    while (i < n && Character.isDigit(sql.charAt(i))) i++;
                }
                String num = sql.substring(start, i);
                if (!isFloat) {
                    if (num.length() > 10 || Long.parseLong(num) > MAX_INT_MAGNITUDE)
                        throw QoErrors.invalidValue("integer literal exceeds 32 bit: " + num, start);
                    out.add(new Token(TokenType.INTEGER, num, start, i - start, null));
                } else {
                    out.add(new Token(TokenType.FLOAT, num, start, i - start, null));
                }
            } else if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { sb.append('\''); i += 2; continue; }
                        closed = true; i++; break;
                    }
                    sb.append(d); i++;
                }
                if (!closed) throw QoErrors.syntax("unterminated string literal", start);
                out.add(new Token(TokenType.STRING, sb.toString(), start, i - start, null));
            } else {
                String sym = null;
                if (i + 1 < n) {
                    String two = sql.substring(i, i + 2);
                    if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=")) sym = two;
                }
                if (sym == null && "(),;.*+-/=<>".indexOf(c) >= 0) sym = String.valueOf(c);
                if (sym == null) throw QoErrors.syntax("illegal character '" + c + "'", start);
                i += sym.length();
                out.add(new Token(TokenType.SYMBOL, sym, start, sym.length(), null));
            }
        }
        out.add(new Token(TokenType.EOF, "", n, 0, null));
        return new TokenStream(out);
    }
}
