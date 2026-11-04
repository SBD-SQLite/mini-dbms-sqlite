package sqlike.qp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import sqlike.contract.IQueryProcessor;

/**
 * Multi-client TCP server for Query Processor matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class QueryServer implements AutoCloseable {
    private final IQueryProcessor processor;
    private final QpConfiguration configuration;
    private ServerSocket listener;
    private ExecutorService connectionPool;
    private final ConcurrentMap<UUID, ClientSessionHandler> handlers;
    private final AtomicBoolean running;

    public QueryServer(IQueryProcessor processor, QpConfiguration configuration) {
        this.processor = Objects.requireNonNull(processor, "IQueryProcessor cannot be null in QueryServer");
        this.configuration = Objects.requireNonNull(configuration, "QpConfiguration cannot be null in QueryServer");
        this.handlers = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            this.listener = new ServerSocket(configuration.getPort());
            this.connectionPool = Executors.newFixedThreadPool(configuration.getMaxClients());
            Thread acceptThread = new Thread(this::acceptLoop, "sqlike-query-server-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            running.set(false);
            throw new UncheckedIOException("Failed to start QueryServer on port " + configuration.getPort(), e);
        }
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        }
        closeAllClients();
        if (connectionPool != null) {
            connectionPool.shutdownNow();
        }
    }

    public int getBoundPort() {
        return listener != null ? listener.getLocalPort() : configuration.getPort();
    }

    private void acceptLoop() {
        while (running.get() && listener != null && !listener.isClosed()) {
            try {
                Socket clientSocket = listener.accept();
                registerClient(clientSocket);
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void registerClient(Socket socket) {
        UUID id = UUID.randomUUID();
        ClientSessionHandler handler = new ClientSessionHandler(socket, processor, new ProtocolCodec());
        handlers.put(id, handler);
        connectionPool.submit(() -> {
            try {
                handler.run();
            } finally {
                handlers.remove(id);
            }
        });
    }

    private void closeAllClients() {
        for (ClientSessionHandler handler : handlers.values()) {
            try {
                handler.close();
            } catch (RuntimeException ignored) {
            }
        }
        handlers.clear();
    }

    @Override
    public void close() {
        stop();
    }
}

