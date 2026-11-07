package sqlike.frm.checkpoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Append-only list of base snapshots and WAL ranges abandoned by point-in-time recovery.
 *
 * <p>One entry per line: {@code SNAPSHOT|id|baseLSN|checkpointLSN|createdAtMillis} or
 * {@code ABANDON|fromLSN|toLSN}. A {@code null} path keeps the catalog in memory.
 */
public class SnapshotCatalog {

    public record SnapshotEntry(String snapshotId, long baseLSN, long checkpointLSN, Instant createdAt) {
    }

    private record AbandonedRange(long fromLSN, long toLSN) {
        boolean contains(long lsn) {
            return lsn >= fromLSN && lsn <= toLSN;
        }
    }

    private final List<Object> entries = new ArrayList<>();
    private final Path path;

    public SnapshotCatalog(Path path) {
        this.path = path;
        if (path != null && Files.exists(path)) {
            load();
        }
    }

    public static SnapshotCatalog inMemory() {
        return new SnapshotCatalog(null);
    }

    public synchronized void recordSnapshot(CheckpointInfo info) {
        Objects.requireNonNull(info, "info");
        String id = info.snapshotId();
        if (id.contains("|") || id.contains("\n")) {
            throw new IllegalArgumentException("snapshotId must not contain '|' or newlines");
        }
        SnapshotEntry entry = new SnapshotEntry(
                id, info.flushedThroughLSN(), info.checkpointLSN(), info.createdAt());
        append("SNAPSHOT|" + id + "|" + entry.baseLSN() + "|" + entry.checkpointLSN() + "|"
                + entry.createdAt().toEpochMilli());
        entries.add(entry);
    }

    public synchronized void recordAbandoned(long fromLSN, long toLSN) {
        if (fromLSN <= 0 || toLSN < fromLSN) {
            throw new IllegalArgumentException("invalid abandoned range " + fromLSN + ".." + toLSN);
        }
        append("ABANDON|" + fromLSN + "|" + toLSN);
        entries.add(new AbandonedRange(fromLSN, toLSN));
    }

    public synchronized boolean isAbandoned(long lsn) {
        for (Object entry : entries) {
            if (entry instanceof AbandonedRange range && range.contains(lsn)) {
                return true;
            }
        }
        return false;
    }

    /** Latest usable snapshot whose base LSN is at or before {@code targetLSN}. */
    public synchronized Optional<SnapshotEntry> latestUsableFor(long targetLSN) {
        SnapshotEntry best = null;
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof SnapshotEntry snapshot)) {
                continue;
            }
            if (snapshot.baseLSN() > targetLSN || invalidatedLater(i, snapshot.baseLSN())) {
                continue;
            }
            if (best == null || snapshot.baseLSN() >= best.baseLSN()) {
                best = snapshot;
            }
        }
        return Optional.ofNullable(best);
    }

    public synchronized List<SnapshotEntry> snapshots() {
        List<SnapshotEntry> result = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof SnapshotEntry snapshot) {
                result.add(snapshot);
            }
        }
        return result;
    }

    private boolean invalidatedLater(int index, long baseLSN) {
        for (int j = index + 1; j < entries.size(); j++) {
            if (entries.get(j) instanceof AbandonedRange range && range.contains(baseLSN)) {
                return true;
            }
        }
        return false;
    }

    private void append(String line) {
        if (path == null) {
            return;
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                channel.write(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("cannot write snapshot catalog " + path, error);
        }
    }

    private void load() {
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|");
                switch (parts[0]) {
                    case "SNAPSHOT" -> entries.add(new SnapshotEntry(parts[1],
                            Long.parseLong(parts[2]), Long.parseLong(parts[3]),
                            Instant.ofEpochMilli(Long.parseLong(parts[4]))));
                    case "ABANDON" -> entries.add(new AbandonedRange(
                            Long.parseLong(parts[1]), Long.parseLong(parts[2])));
                    default -> throw new IllegalStateException("corrupt snapshot catalog line: " + line);
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read snapshot catalog " + path, error);
        } catch (RuntimeException error) {
            throw new IllegalStateException("corrupt snapshot catalog " + path, error);
        }
    }
}
