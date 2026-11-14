package sqlike.qp;

import java.util.Objects;
import java.util.Optional;

/**
 * Wire frame carrying schema, row, or execution summary between server and client.
 */
public final class ProtocolFrame {
    private final FrameKind kind;
    private final Optional<Schema> schema;
    private final Optional<Row> row;
    private final Optional<ExecutionSummary> summary;

    public ProtocolFrame(
            FrameKind kind,
            Optional<Schema> schema,
            Optional<Row> row,
            Optional<ExecutionSummary> summary) {
        this.kind = Objects.requireNonNull(kind, "FrameKind cannot be null in ProtocolFrame");
        this.schema = schema == null ? Optional.empty() : schema;
        this.row = row == null ? Optional.empty() : row;
        this.summary = summary == null ? Optional.empty() : summary;
    }

    public FrameKind getKind() {
        return kind;
    }

    public Optional<Schema> getSchema() {
        return schema;
    }

    public Optional<Row> getRow() {
        return row;
    }

    public Optional<ExecutionSummary> getSummary() {
        return summary;
    }
}

