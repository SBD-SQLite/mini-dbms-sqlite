package sqlike.qo;

import java.util.Locale;
import java.util.Optional;

public enum Keyword {
    SELECT, FROM, WHERE, ORDER, BY, ASC, DESC, JOIN, INNER, ON, NATURAL, AND,
    INSERT, INTO, VALUES, UPDATE, SET, DELETE, CREATE, INDEX, USING, DROP, TABLE,
    BEGIN, TRANSACTION, COMMIT, ABORT,
    // outside the agreed contract, so parser can recognize tp reject w UNSUPPORTED_OPERATION
    OR, NOT, NULL, LIMIT, AS, DISTINCT, GROUP, HAVING;

    public static Optional<Keyword> lookup(String word) {
        try { return Optional.of(valueOf(word.toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
