package sqlike.qp;

import java.nio.file.Path;
import java.util.Objects;
import sqlike.shared.IndexType;

/**
 * Query Processor configuration matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class QpConfiguration {
    private final int port;
    private final int maxClients;
    private final long joinBufferBytes;
    private final IndexType defaultIndexKind;
    private final Path temporaryDirectory;

    public QpConfiguration(
            int port,
            int maxClients,
            long joinBufferBytes,
            IndexType defaultIndexKind,
            Path temporaryDirectory) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (maxClients <= 0) {
            throw new IllegalArgumentException("maxClients must be positive: " + maxClients);
        }
        if (joinBufferBytes <= 0) {
            throw new IllegalArgumentException("joinBufferBytes must be positive: " + joinBufferBytes);
        }
        this.port = port;
        this.maxClients = maxClients;
        this.joinBufferBytes = joinBufferBytes;
        this.defaultIndexKind = Objects.requireNonNull(defaultIndexKind, "defaultIndexKind cannot be null");
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory cannot be null");
    }

    public static QpConfiguration defaults() {
        return new QpConfiguration(
                5432,
                16,
                64L * 1024L,
                IndexType.HASH,
                Path.of(System.getProperty("java.io.tmpdir", "/tmp")));
    }

    /** Options: --port, --max-clients, --join-buffer, --index, --temp. */
    public static QpConfiguration fromArgs(String[] args) {
        QpConfiguration base = defaults();
        int port = base.port;
        int maxClients = base.maxClients;
        long joinBufferBytes = base.joinBufferBytes;
        IndexType indexKind = base.defaultIndexKind;
        Path temporaryDirectory = base.temporaryDirectory;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for option " + option);
                }
                String value = args[++i];
                try {
                    switch (option) {
                        case "--port" -> port = Integer.parseInt(value);
                        case "--max-clients" -> maxClients = Integer.parseInt(value);
                        case "--join-buffer" -> joinBufferBytes = Long.parseLong(value);
                        case "--index" -> indexKind = IndexType.valueOf(value.toUpperCase());
                        case "--temp" -> temporaryDirectory = Path.of(value);
                        default -> throw new IllegalArgumentException("Unknown option: " + option);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid number for " + option + ": " + value);
                }
            }
        }
        return new QpConfiguration(port, maxClients, joinBufferBytes, indexKind, temporaryDirectory);
    }

    public int getPort() {
        return port;
    }

    public int port() {
        return port;
    }

    public int getMaxClients() {
        return maxClients;
    }

    public int maxClients() {
        return maxClients;
    }

    public long getJoinBufferBytes() {
        return joinBufferBytes;
    }

    public long joinBufferBytes() {
        return joinBufferBytes;
    }

    public IndexType getDefaultIndexKind() {
        return defaultIndexKind;
    }

    public IndexType defaultIndexKind() {
        return defaultIndexKind;
    }

    public Path getTemporaryDirectory() {
        return temporaryDirectory;
    }

    public Path temporaryDirectory() {
        return temporaryDirectory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof QpConfiguration that) {
            return port == that.port
                    && maxClients == that.maxClients
                    && joinBufferBytes == that.joinBufferBytes
                    && defaultIndexKind == that.defaultIndexKind
                    && Objects.equals(temporaryDirectory, that.temporaryDirectory);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(port, maxClients, joinBufferBytes, defaultIndexKind, temporaryDirectory);
    }

    @Override
    public String toString() {
        return "QpConfiguration[port=" + port
                + ", maxClients=" + maxClients
                + ", joinBufferBytes=" + joinBufferBytes
                + ", defaultIndexKind=" + defaultIndexKind
                + ", temporaryDirectory=" + temporaryDirectory + "]";
    }
}

