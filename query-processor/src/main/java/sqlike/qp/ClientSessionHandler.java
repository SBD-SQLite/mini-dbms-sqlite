package sqlike.qp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import sqlike.contract.IQueryProcessor;
import sqlike.qp.port.QpContracts.DbmsError;
import sqlike.qp.port.QpContracts.SessionId;
import sqlike.shared.QueryResult;
import sqlike.shared.ResultColumn;
import sqlike.shared.ResultKind;
import sqlike.shared.Session;
import sqlike.shared.ColumnSchema;

/**
 * Per-connection session handler for QueryServer matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class ClientSessionHandler implements Runnable, AutoCloseable {
    private final Socket socket;
    private final IQueryProcessor processor;
    private SessionId sessionId;
    private final ProtocolCodec codec;
    private SocketResultSink sink;
    private ExecutorService statementExecutor;
    private final AtomicBoolean closed;

    public ClientSessionHandler(Socket socket, IQueryProcessor processor, ProtocolCodec codec) {
        this.socket = Objects.requireNonNull(socket, "Socket cannot be null in ClientSessionHandler");
        this.processor = Objects.requireNonNull(processor, "IQueryProcessor cannot be null in ClientSessionHandler");
        this.codec = Objects.requireNonNull(codec, "ProtocolCodec cannot be null in ClientSessionHandler");
        this.closed = new AtomicBoolean(false);
        this.statementExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void run() {
        try {
            if (processor instanceof QueryProcessor qp) {
                this.sessionId = qp.openSessionId();
            } else {
                this.sessionId = new SessionId(UUID.randomUUID());
            }
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            this.sink = new SocketResultSink(output, codec);

            while (!closed.get() && !socket.isClosed()) {
                Optional<String> query = codec.readQuery(input);
                if (query.isEmpty()) {
                    break;
                }
                executeQuery(query.get());
            }
        } catch (Throwable cause) {
            handleDisconnect(cause);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (statementExecutor != null) {
            statementExecutor.shutdownNow();
        }
        if (sessionId != null && processor instanceof QueryProcessor qp) {
            try {
                qp.closeSession(sessionId);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void executeQuery(String sql) {
        if (processor instanceof QueryProcessor qp && sessionId != null) {
            qp.execute(sessionId, sql, sink);
            return;
        }
        String sid = sessionId != null ? sessionId.value().toString() : UUID.randomUUID().toString();
        QueryResult result = processor.execute(sql, new Session(sid, Optional.empty()));
        if (result.kind() == ResultKind.ROWS) {
            Schema schema = Schema.of(result.columns().stream()
                    .map(rc -> new ColumnSchema(rc.name(), rc.dataType(), Optional.empty()))
                    .toList());
            sink.onSchema(schema);
            for (var rowVals : result.rows()) {
                sink.onRow(Row.of(rowVals));
            }
        }
        sink.onFinish(ExecutionSummary.fromQueryResult(result));
    }

    private void handleDisconnect(Throwable cause) {
        if (!closed.get() && sink != null && cause != null) {
            try {
                sink.onFinish(ExecutionSummary.failure(new DbmsError(cause.getMessage())));
            } catch (RuntimeException ignored) {
            }
        }
    }
}

