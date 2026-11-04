package sqlike.qp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.util.Objects;
import java.util.Optional;

/**
 * Interactive CLI socket client matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class CliClient implements AutoCloseable {
    private Socket socket;
    private final ProtocolCodec codec;
    private final StatementReader reader;
    private final ConsoleResultSink output;

    public CliClient(ProtocolCodec codec, StatementReader reader, ConsoleResultSink output) {
        this.codec = Objects.requireNonNull(codec, "ProtocolCodec cannot be null in CliClient");
        this.reader = Objects.requireNonNull(reader, "StatementReader cannot be null in CliClient");
        this.output = Objects.requireNonNull(output, "ConsoleResultSink cannot be null in CliClient");
    }

    public void connect(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be null or blank in CliClient.connect");
        }
        try {
            this.socket = new Socket(host, port);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to connect to " + host + ":" + port, e);
        }
    }

    public void run(BufferedReader input) {
        if (input == null) {
            throw new IllegalArgumentException("BufferedReader input cannot be null in CliClient.run");
        }
        if (socket == null || socket.isClosed()) {
            throw new IllegalStateException("CliClient is not connected");
        }
        try {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            while (true) {
                Optional<String> statement = reader.readStatement(input);
                if (statement.isEmpty()) {
                    break;
                }
                String sql = statement.get();
                if (StatementReader.isClientCommand(sql)) {
                    String cmd = sql.substring(1).trim().toLowerCase();
                    if (cmd.equals("q") || cmd.equals("quit") || cmd.equals("exit")) {
                        break;
                    }
                    continue;
                }
                codec.writeQuery(out, sql);
                receiveUntilFinish();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("I/O error during CLI session", e);
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private ExecutionSummary receiveUntilFinish() {
        if (socket == null || socket.isClosed()) {
            throw new IllegalStateException("Socket is not connected in receiveUntilFinish");
        }
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (true) {
                ProtocolFrame frame = codec.readFrame(in);
                switch (frame.getKind()) {
                    case SCHEMA -> frame.getSchema().ifPresent(output::onSchema);
                    case ROW -> frame.getRow().ifPresent(output::onRow);
                    case SUMMARY -> {
                        ExecutionSummary summary = frame.getSummary()
                                .orElseGet(() -> ExecutionSummary.ok(0L, ""));
                        output.onFinish(summary);
                        return summary;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to receive frames from server", e);
        }
    }
}

