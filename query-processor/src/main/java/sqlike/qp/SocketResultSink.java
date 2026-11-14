package sqlike.qp;

import java.io.DataOutputStream;
import java.util.Objects;

/**
 * ResultSink implementation that streams schema, rows, and summary frames over a socket output stream.
 */
public final class SocketResultSink implements ResultSink {
    private final DataOutputStream output;
    private final ProtocolCodec codec;

    public SocketResultSink(DataOutputStream output, ProtocolCodec codec) {
        this.output = Objects.requireNonNull(output, "DataOutputStream cannot be null in SocketResultSink");
        this.codec = Objects.requireNonNull(codec, "ProtocolCodec cannot be null in SocketResultSink");
    }

    @Override
    public void onSchema(Schema schema) {
        codec.writeSchema(output, schema);
    }

    @Override
    public void onRow(Row row) {
        codec.writeRow(output, row);
    }

    @Override
    public void onFinish(ExecutionSummary summary) {
        codec.writeSummary(output, summary);
    }
}

