package sqlike.qp;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import sqlike.shared.ErrorInfo;
import sqlike.shared.QueryResult;
import sqlike.shared.ResultColumn;
import sqlike.shared.Value;

/**
 * Renders a QueryResult as text: a bordered table for rows, a message for affected rows
 * and transaction commands, and a single "ERROR [code]" line for failures.
 */
public final class ResultPrinter {
    private static final String NULL_TEXT = "NULL";

    private ResultPrinter() {
    }

    public static void print(QueryResult result, PrintStream out) {
        out.print(format(result));
        out.flush();
    }

    public static String format(QueryResult result) {
        if (result == null) {
            return "ERROR: no result\n";
        }
        switch (result.kind()) {
            case ROWS:
                return formatTable(result) + result.message() + "\n";
            case AFFECTED_ROWS:
            case TRANSACTION:
                return result.message() + "\n";
            case ERROR:
            default:
                return formatError(result);
        }
    }

    private static String formatError(QueryResult result) {
        if (result.error().isEmpty()) {
            return "ERROR: " + result.message() + "\n";
        }
        ErrorInfo error = result.error().get();
        String position = error.position().map(p -> " at position " + p).orElse("");
        return "ERROR [" + error.component() + " " + error.code() + "]" + position + ": " + error.message() + "\n";
    }

    private static String formatTable(QueryResult result) {
        List<String> headers = new ArrayList<>();
        for (ResultColumn column : result.columns()) {
            headers.add(column.name());
        }
        List<List<String>> cells = new ArrayList<>();
        int width = headers.size();
        for (List<Value> row : result.rows()) {
            List<String> line = new ArrayList<>();
            for (Value value : row) {
                line.add(formatValue(value));
            }
            width = Math.max(width, line.size());
            cells.add(line);
        }
        if (width == 0) {
            return "";
        }

        int[] widths = new int[width];
        measure(headers, widths);
        for (List<String> line : cells) {
            measure(line, widths);
        }

        StringBuilder out = new StringBuilder();
        String border = border(widths);
        out.append(border);
        if (!headers.isEmpty()) {
            appendRow(out, headers, widths);
            out.append(border);
        }
        for (List<String> line : cells) {
            appendRow(out, line, widths);
        }
        out.append(border);
        return out.toString();
    }

    public static String formatValue(Value value) {
        if (value == null) {
            return NULL_TEXT;
        }
        if (value.integerValue().isPresent()) {
            return String.valueOf(value.integerValue().get());
        }
        if (value.floatValue().isPresent()) {
            return String.valueOf(value.floatValue().get());
        }
        return value.textValue().orElse(NULL_TEXT);
    }

    private static void measure(List<String> line, int[] widths) {
        for (int i = 0; i < line.size(); i++) {
            widths[i] = Math.max(widths[i], line.get(i).length());
        }
    }

    private static String border(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        for (int w : widths) {
            border.append("-".repeat(w + 2)).append('+');
        }
        return border.append('\n').toString();
    }

    private static void appendRow(StringBuilder out, List<String> line, int[] widths) {
        out.append('|');
        for (int i = 0; i < widths.length; i++) {
            String cell = i < line.size() ? line.get(i) : "";
            out.append(' ').append(cell).append(" ".repeat(widths[i] - cell.length())).append(" |");
        }
        out.append('\n');
    }
}
