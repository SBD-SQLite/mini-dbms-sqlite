package sqlike.qp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Optional;

/**
 * Reads complete SQL statements from a character stream. A statement ends at a semicolon
 * outside single-quoted literals ('' inside a literal is an escaped quote) and may span lines.
 */
public final class StatementReader {
    private final BufferedReader reader;

    public StatementReader(Reader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("Reader cannot be null in StatementReader");
        }
        this.reader = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
    }

    public static boolean isClientCommand(String statement) {
        return statement != null && statement.startsWith("\\");
    }

    /**
     * Returns the next statement without its trailing semicolon, or empty at end of input.
     * A line starting with a backslash is a client command (e.g. \q) and is returned whole, without a semicolon.
     * Trailing text without a semicolon at end of input is returned as a final statement.
     */
    public Optional<String> nextStatement() throws IOException {
        StringBuilder statement = new StringBuilder();
        boolean inLiteral = false;
        int ch;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;
            if (c == '﻿') {
                // Byte order mark from piped or file input.
                continue;
            }
            if (c == '\\' && !inLiteral && statement.toString().isBlank()) {
                String rest = reader.readLine();
                return Optional.of("\\" + (rest == null ? "" : rest.trim()));
            }
            if (c == '\'') {
                // '' inside a literal closes and reopens it, so toggling keeps the state correct.
                inLiteral = !inLiteral;
            } else if (c == ';' && !inLiteral) {
                String sql = statement.toString().trim();
                if (sql.isEmpty()) {
                    continue;
                }
                return Optional.of(sql);
            }
            statement.append(c);
        }
        String rest = statement.toString().trim();
        return rest.isEmpty() ? Optional.empty() : Optional.of(rest);
    }
}
