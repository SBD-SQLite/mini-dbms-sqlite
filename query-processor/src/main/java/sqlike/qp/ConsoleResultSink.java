package sqlike.qp;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sqlike.shared.ColumnSchema;
import sqlike.shared.Value;

/**
 * ResultSink implementation that formats and writes query results to a PrintWriter.
 */
public final class ConsoleResultSink implements ResultSink {
    private final PrintWriter output;
    private Optional<Schema> schema;
    private long rowCount;

    public ConsoleResultSink(PrintWriter output) {
        this.output = Objects.requireNonNull(output, "PrintWriter output cannot be null in ConsoleResultSink");
        this.schema = Optional.empty();
        this.rowCount = 0L;
    }

    @Override
    public void onSchema(Schema schema) {
        this.schema = Optional.ofNullable(schema);
        this.rowCount = 0L;
        if (schema != null) {
            printHeader(schema);
        }
    }

    @Override
    public void onRow(Row row) {
        if (row == null) {
            throw new IllegalArgumentException("Row cannot be null in ConsoleResultSink.onRow");
        }
        List<String> formatted = new ArrayList<>(row.size());
        for (Value value : row.values()) {
            formatted.add(formatValue(value));
        }
        output.println("| " + String.join(" | ", formatted) + " |");
        rowCount++;
    }

    @Override
    public void onFinish(ExecutionSummary summary) {
        if (summary == null) {
            output.println("ERROR: Null execution summary");
        } else if (!summary.isSuccess()) {
            output.println("ERROR: " + summary.getMessage());
        } else if (schema.isPresent()) {
            output.println(rowCount + " row(s) returned");
        } else if (!summary.getMessage().isBlank()) {
            output.println(summary.getMessage());
        } else {
            output.println(summary.getAffectedRows() + " row(s) affected");
        }
        output.flush();
    }

    private String formatValue(Value value) {
        return ResultPrinter.formatValue(value);
    }

    private void printHeader(Schema schema) {
        List<String> names = new ArrayList<>(schema.columnCount());
        for (ColumnSchema col : schema.getColumns()) {
            names.add(col.name());
        }
        if (!names.isEmpty()) {
            output.println("| " + String.join(" | ", names) + " |");
        }
    }

    public Optional<Schema> getSchema() {
        return schema;
    }

    public long getRowCount() {
        return rowCount;
    }
}

