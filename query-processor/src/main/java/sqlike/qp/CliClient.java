package sqlike.qp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Interactive CLI socket client matching CLASS DIAGRAM FIX (Tab 4).
 * Usage: java sqlike.qp.CliClient [host] [port]
 */
public final class CliClient implements AutoCloseable {
    private static final String PROMPT = "sqlike> ";

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private final ProtocolCodec codec;
    private final StatementReader reader;
    private final ConsoleResultSink output;
    private boolean interactive;

    public CliClient(ProtocolCodec codec, StatementReader reader, ConsoleResultSink output) {
        this.codec = Objects.requireNonNull(codec, "ProtocolCodec cannot be null in CliClient");
        this.reader = Objects.requireNonNull(reader, "StatementReader cannot be null in CliClient");
        this.output = Objects.requireNonNull(output, "ConsoleResultSink cannot be null in CliClient");
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port;
        try {
            port = args.length > 1 ? Integer.parseInt(args[1]) : QpConfiguration.defaults().getPort();
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + args[1]);
            System.exit(2);
            return;
        }
        PrintWriter console = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        ConsoleResultSink sink = new ConsoleResultSink(console);
        try (CliClient client = new CliClient(new ProtocolCodec(), new StatementReader(), sink)) {
            client.connect(host, port);
            client.interactive = true;
            sink.printMessage("Connected to SQLike at " + host + ":" + port
                    + ". End statements with ';'. Type \\q to quit.");
            client.run(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
            sink.printMessage("Bye.");
        } catch (UncheckedIOException e) {
            System.err.println("Cannot reach SQLike server at " + host + ":" + port + ": "
                    + Optional.ofNullable(e.getCause()).map(Throwable::getMessage).orElse(e.getMessage()));
            System.exit(1);
        }
    }

    public void connect(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host cannot be null or blank in CliClient.connect");
        }
        try {
            this.socket = new Socket(host, port);
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
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
        while (true) {
            if (interactive) {
                output.printPrompt(PROMPT);
            }
            Optional<String> statement = reader.readStatement(input);
            if (statement.isEmpty()) {
                break;
            }
            String sql = statement.get();
            if (StatementReader.isClientCommand(sql)) {
                String cmd = sql.substring(1).trim().toLowerCase(Locale.ROOT);
                if (cmd.equals("q") || cmd.equals("quit") || cmd.equals("exit")) {
                    break;
                }
                output.printMessage("Unknown command: " + sql + " (use \\q to quit)");
                continue;
            }
            try {
                codec.writeQuery(out, sql);
                receiveUntilFinish();
            } catch (UncheckedIOException e) {
                output.printMessage("Connection to server lost: "
                        + Optional.ofNullable(e.getCause()).map(Throwable::getMessage).orElse(e.getMessage()));
                break;
            }
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
    }
}
