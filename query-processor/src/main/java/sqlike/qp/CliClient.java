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
        String host = "localhost";
        int port = QpConfiguration.defaults().getPort();
        java.nio.file.Path scriptFile = null;
        int positional = 0;
        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--host", "-h" -> {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        host = args[++i];
                    }
                    case "--port", "-p" -> {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        port = Integer.parseInt(args[++i]);
                    }
                    case "-f", "--file" -> {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                        scriptFile = java.nio.file.Path.of(args[++i]);
                    }
                    default -> {
                        if (positional == 0) {
                            host = arg;
                            positional++;
                        } else if (positional == 1) {
                            port = Integer.parseInt(arg);
                            positional++;
                        } else {
                            throw new IllegalArgumentException("Unexpected argument: " + arg);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: java sqlike.qp.CliClient [host] [port] | [--host H] [--port P] [-f FILE]");
            System.exit(2);
            return;
        }
        PrintWriter console = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        ConsoleResultSink sink = new ConsoleResultSink(console);
        try (CliClient client = new CliClient(new ProtocolCodec(), new StatementReader(), sink)) {
            client.connect(host, port);
            if (scriptFile != null) {
                client.interactive = false;
                try (BufferedReader fileReader = java.nio.file.Files.newBufferedReader(scriptFile, StandardCharsets.UTF_8)) {
                    client.run(fileReader);
                } catch (IOException e) {
                    System.err.println("Failed to read script file " + scriptFile + ": " + e.getMessage());
                    System.exit(1);
                }
            } else {
                client.interactive = true;
                sink.printMessage("Connected to SQLike at " + host + ":" + port
                        + ". End statements with ';'. Type \\help for commands or \\q / EXIT; to quit.");
                client.run(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
                sink.printMessage("Bye.");
            }
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
                if (cmd.equals("help") || cmd.equals("?")) {
                    output.printMessage("SQLike CLI commands:");
                    output.printMessage("  <SQL>;       Execute SQL statement (multi-line supported, end with ';')");
                    output.printMessage("  BEGIN;       Start an explicit transaction (or BEGIN TRANSACTION;)");
                    output.printMessage("  COMMIT;      Commit the active explicit transaction");
                    output.printMessage("  ABORT;       Abort and rollback the active explicit transaction");
                    output.printMessage("  SHOW LOG [txId|CURRENT];  Show the write-ahead log (same as \\log)");
                    output.printMessage("  \\log [txId|current]  Show the log: all records, one transaction, or this session's");
                    output.printMessage("  \\q | EXIT;   Disconnect and exit client");
                    continue;
                }
                if (cmd.equals("log") || cmd.startsWith("log ")) {
                    sql = "SHOW LOG " + cmd.substring(3).trim();
                } else {
                    output.printMessage("Unknown command: " + sql + " (use \\help or \\q to quit)");
                    continue;
                }
            }
            String normalized = sql.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("exit") || normalized.equals("quit")) {
                break;
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
