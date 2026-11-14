package sqlike.frm.pitr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;

/** Durable "point-in-time recovery in progress" flag stored as {@code TIME|<instant>} or {@code TRANSACTION|<txId>}. */
public final class PitrMarker {

    private final Path path;

    public PitrMarker(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath();
    }

    public Path path() {
        return path;
    }

    /** Writes the target durably: temp file, force, atomic rename, then force the folder. */
    public synchronized void write(RecoveryTarget target) {
        Objects.requireNonNull(target, "target");
        String line = target.kind() == RecoveryTargetKind.TIME
                ? "TIME|" + target.time().orElseThrow()
                : "TRANSACTION|" + target.txId().orElseThrow();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            forceFolder();
        } catch (IOException error) {
            throw new UncheckedIOException("cannot write PITR marker " + path, error);
        }
    }

    /** Target of an interrupted restore, or empty when no restore is in progress. */
    public synchronized Optional<RecoveryTarget> read() {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String line = Files.readString(path, StandardCharsets.UTF_8).trim();
            int bar = line.indexOf('|');
            if (bar < 0) {
                throw new IllegalStateException("corrupt PITR marker " + path + ": " + line);
            }
            String kind = line.substring(0, bar);
            String value = line.substring(bar + 1);
            return Optional.of(switch (kind) {
                case "TIME" -> new RecoveryTarget(RecoveryTargetKind.TIME,
                        Optional.of(Instant.parse(value)), Optional.empty());
                case "TRANSACTION" -> new RecoveryTarget(RecoveryTargetKind.TRANSACTION,
                        Optional.empty(), Optional.of(Long.parseLong(value)));
                default -> throw new IllegalStateException("corrupt PITR marker " + path + ": " + line);
            });
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read PITR marker " + path, error);
        }
    }

    /** Removes the flag durably and does nothing when no flag exists. */
    public synchronized void clear() {
        try {
            if (Files.deleteIfExists(path)) {
                forceFolder();
            }
        } catch (IOException error) {
            throw new UncheckedIOException("cannot remove PITR marker " + path, error);
        }
    }

    private void forceFolder() {
        try (FileChannel folder = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
            folder.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort because some platforms cannot open a directory as a channel.
        }
    }
}
