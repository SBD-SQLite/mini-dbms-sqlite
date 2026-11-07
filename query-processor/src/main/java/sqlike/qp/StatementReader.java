package sqlike.qp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads complete SQL statements from a character stream matching CLASS DIAGRAM FIX (Tab 4).
 * A statement ends at a semicolon outside single-quoted literals ('' inside a literal is an escaped quote)
 * and may span lines.
 */
public final class StatementReader {
    private final BufferedReader reader;
    private final StringBuilder pending;
    private boolean inQuotedText;

    public StatementReader() {
        this.reader = null;
        this.pending = new StringBuilder();
        this.inQuotedText = false;
    }

    public StatementReader(Reader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("Reader cannot be null in StatementReader");
        }
        this.reader = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
        this.pending = new StringBuilder();
        this.inQuotedText = false;
    }

    public static boolean isClientCommand(String statement) {
        return statement != null && statement.startsWith("\\");
    }

    public Optional<String> readStatement(BufferedReader input) {
        if (input == null) {
            throw new IllegalArgumentException("BufferedReader cannot be null in readStatement");
        }
        try {
            return readFromReader(input);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read statement", e);
        }
    }

    /**
     * Returns the next statement without its trailing semicolon, or empty at end of input.
     * A line starting with a backslash is a client command (e.g. \q) and is returned whole, without a semicolon.
     * Trailing text without a semicolon at end of input is returned as a final statement.
     */
    public Optional<String> nextStatement() throws IOException {
        if (reader == null) {
            throw new IllegalStateException("No default Reader configured; use readStatement(BufferedReader)");
        }
        return readFromReader(reader);
    }

    private Optional<String> readFromReader(BufferedReader activeReader) throws IOException {
        pending.setLength(0);
        inQuotedText = false;
        int ch;
        while ((ch = activeReader.read()) != -1) {
            char c = (char) ch;
            if (c == '\uFEFF') {
                // Byte order mark from piped or file input.
                continue;
            }
            if (c == '\\' && !inQuotedText && pending.toString().isBlank()) {
                String rest = activeReader.readLine();
                String cmd = "\\" + (rest == null ? "" : rest.trim());
                return Optional.of(cmd);
            }
            if (c == '\'') {
                // '' inside a literal closes and reopens it, so toggling keeps the state correct.
                inQuotedText = !inQuotedText;
            } else if (c == ';' && !inQuotedText) {
                String sql = pending.toString().trim();
                pending.setLength(0);
                if (sql.isEmpty()) {
                    continue;
                }
                return Optional.of(sql);
            }
            pending.append(c);
            int term = findTerminator(pending.toString());
            if (term >= 0 && !inQuotedText) {
                String sql = pending.substring(0, term).trim();
                pending.delete(0, term + 1);
                if (!sql.isEmpty()) {
                    return Optional.of(sql);
                }
            }
        }
        String rest = pending.toString().trim();
        pending.setLength(0);
        return rest.isEmpty() ? Optional.empty() : Optional.of(rest);
    }

    private int findTerminator(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private boolean isExitCommand(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim().toLowerCase(Locale.ROOT);
        return trimmed.equals("\\q") || trimmed.equals("\\quit") || trimmed.equals("exit") || trimmed.equals("quit");
    }
}
