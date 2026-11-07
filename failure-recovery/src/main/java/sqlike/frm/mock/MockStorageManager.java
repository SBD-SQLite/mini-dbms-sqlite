package sqlike.frm.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import sqlike.contract.IWalDurability;
import sqlike.frm.port.SmPort;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.Value;

/**
 * File-backed stand-in for Storage Manager so FRM can be tested without the real SM (FR-09).
 *
 * <p>Model of the real SM, kept deliberately small:
 * <ul>
 *   <li>The in-memory table maps play the buffer pool. One table counts as one dirty "page" with a
 *       pageLSN equal to the highest LSN written to it since the last flush.</li>
 *   <li>{@link #flushAll()} and {@link #evict(String)} write tables to {@code dataDir/tables}. Both
 *       call {@link IWalDurability#ensureDurable(long)} with the pageLSN first (WAL rule), and the
 *       file is replaced atomically, so a killed process leaves either the old or the new file.</li>
 *   <li>Data that was never flushed is lost when the process is killed: a new instance on the same
 *       {@code dataDir} sees only flushed tables.</li>
 *   <li>Snapshots copy the flushed table files to {@code dataDir/snapshots/<id>}.</li>
 * </ul>
 */
public class MockStorageManager implements SmPort {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String TABLE_SUFFIX = ".tbl";

    private final Path dataDir;
    private final Path tablesDir;
    private final Path snapshotsDir;
    private final Map<String, TreeMap<RID, List<Value>>> tables = new TreeMap<>();
    private final Map<String, Long> dirtyPageLsn = new HashMap<>();
    private IWalDurability wal;

    /** Opens {@code dataDir}, loading every flushed table (a "restart" reads only the disk). */
    public MockStorageManager(Path dataDir) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.tablesDir = dataDir.resolve("tables");
        this.snapshotsDir = dataDir.resolve("snapshots");
        try {
            Files.createDirectories(tablesDir);
            Files.createDirectories(snapshotsDir);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot create " + dataDir, error);
        }
        loadFromDisk();
    }

    /** Connects the WAL gate; FRM is created after SM, so it is set afterwards. */
    public synchronized void setWal(IWalDurability wal) {
        this.wal = Objects.requireNonNull(wal, "wal");
    }

    public Path getDataDir() {
        return dataDir;
    }

    // ------------------------------------------------------------------ SmPort

    @Override
    public synchronized RecordImage readAt(String table, RID rid) {
        requireRidOf(table, rid);
        List<Value> values = tables.getOrDefault(table, new TreeMap<>(RecordCodec.RID_ORDER)).get(rid);
        return values == null ? new RecordImage(false, List.of()) : new RecordImage(true, values);
    }

    @Override
    public synchronized void writeAt(String table, RID rid, RecordImage image, long lsn) {
        requireRidOf(table, rid);
        Objects.requireNonNull(image, "image");
        if (lsn < 0) {
            throw new IllegalArgumentException("lsn must not be negative");
        }
        TreeMap<RID, List<Value>> rows = table(table);
        if (image.exists()) {
            rows.put(rid, List.copyOf(image.values()));
        } else {
            rows.remove(rid);
        }
        dirtyPageLsn.merge(table, lsn, Math::max);
    }

    @Override
    public synchronized void flushAll() {
        for (String table : new ArrayList<>(dirtyPageLsn.keySet())) {
            flushTable(table);
        }
    }

    /** Copies the flushed database into an immutable snapshot (flushes first). */
    @Override
    public synchronized String createSnapshot() {
        flushAll();
        try {
            int number = 1;
            Path target;
            do {
                target = snapshotsDir.resolve(String.format("snap-%04d", number++));
            } while (Files.exists(target));
            Path staging = snapshotsDir.resolve(target.getFileName() + ".tmp");
            deleteRecursively(staging);
            Files.createDirectories(staging);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(tablesDir, "*" + TABLE_SUFFIX)) {
                for (Path file : files) {
                    Files.copy(file, staging.resolve(file.getFileName()));
                }
            }
            moveAtomically(staging, target);
            return target.getFileName().toString();
        } catch (IOException error) {
            throw new UncheckedIOException("createSnapshot failed", error);
        }
    }

    /** Replaces the active tables with a snapshot and drops the old buffer (offline only). */
    @Override
    public synchronized void restoreSnapshot(String snapshotId) {
        Path source = snapshotsDir.resolve(Objects.requireNonNull(snapshotId, "snapshotId"));
        if (!Files.isDirectory(source) || snapshotId.contains("/") || snapshotId.contains("\\")) {
            throw new IllegalArgumentException("unknown snapshot: " + snapshotId);
        }
        try {
            Path staging = dataDir.resolve("tables.restore.tmp");
            deleteRecursively(staging);
            Files.createDirectories(staging);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(source, "*" + TABLE_SUFFIX)) {
                for (Path file : files) {
                    Files.copy(file, staging.resolve(file.getFileName()));
                }
            }
            Path old = dataDir.resolve("tables.old.tmp");
            deleteRecursively(old);
            Files.move(tablesDir, old);
            Files.move(staging, tablesDir);
            deleteRecursively(old);
        } catch (IOException error) {
            throw new UncheckedIOException("restoreSnapshot " + snapshotId + " failed", error);
        }
        dirtyPageLsn.clear();
        loadFromDisk();
    }

    // ------------------------------------------------------------------ test helpers

    /** Writes one table to disk now, like a buffer eviction (STEAL); still obeys WAL. */
    public synchronized void evict(String table) {
        if (dirtyPageLsn.containsKey(table)) {
            flushTable(table);
        }
    }

    /** Puts a baseline record without a log record (fixture data, pageLSN 0). */
    public synchronized void seed(String table, RID rid, List<Value> values) {
        writeAt(table, rid, new RecordImage(true, values), 0L);
    }

    /**
     * Loads a fixture file and flushes it as the baseline. Format, one record per line:
     * {@code table | page | slot | TYPE value | TYPE value ...}; {@code #} starts a comment.
     */
    public synchronized void loadFixture(Path fixture) {
        try {
            for (String raw : Files.readAllLines(fixture, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|");
                if (parts.length < 3) {
                    throw new IllegalArgumentException("bad fixture line: " + raw);
                }
                String table = parts[0].strip();
                RID rid = new RID(new PageId(table, Long.parseLong(parts[1].strip())),
                        Integer.parseInt(parts[2].strip()));
                List<Value> values = new ArrayList<>();
                for (int i = 3; i < parts.length; i++) {
                    values.add(RecordCodec.parseFixtureValue(parts[i]));
                }
                seed(table, rid, values);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("cannot read fixture " + fixture, error);
        }
        flushAll();
    }

    /** Copy of every record currently in {@code table} (buffer view). */
    public synchronized Map<RID, RecordImage> dump(String table) {
        Map<RID, RecordImage> copy = new LinkedHashMap<>();
        tables.getOrDefault(table, new TreeMap<>(RecordCodec.RID_ORDER))
                .forEach((rid, values) -> copy.put(rid, new RecordImage(true, values)));
        return copy;
    }

    /** Copy of every non-empty table currently held. */
    public synchronized Map<String, Map<RID, RecordImage>> dumpAll() {
        Map<String, Map<RID, RecordImage>> all = new TreeMap<>();
        for (String table : tables.keySet()) {
            Map<RID, RecordImage> rows = dump(table);
            if (!rows.isEmpty()) {
                all.put(table, rows);
            }
        }
        return all;
    }

    public synchronized boolean hasDirtyTables() {
        return !dirtyPageLsn.isEmpty();
    }

    public synchronized List<String> listSnapshots() {
        List<String> ids = new ArrayList<>();
        try (Stream<Path> entries = Files.list(snapshotsDir)) {
            entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.endsWith(".tmp"))
                    .sorted()
                    .forEach(ids::add);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot list snapshots", error);
        }
        return ids;
    }

    /** Deletes {@code dataDir} completely, so a scenario starts from a known empty state. */
    public static void wipe(Path dataDir) {
        try {
            deleteRecursively(dataDir);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot delete " + dataDir, error);
        }
    }

    // ------------------------------------------------------------------ internals

    private void flushTable(String table) {
        long pageLsn = dirtyPageLsn.getOrDefault(table, 0L);
        if (pageLsn > 0) {
            if (wal == null) {
                throw new IllegalStateException("WAL gate is not set; refusing to write " + table);
            }
            wal.ensureDurable(pageLsn); // a failure here keeps the table dirty
        }
        List<String> lines = new ArrayList<>();
        table(table).forEach((rid, values) -> lines.add(RecordCodec.encodeLine(rid, values)));
        Path target = tablesDir.resolve(table + TABLE_SUFFIX);
        Path temp = tablesDir.resolve(table + TABLE_SUFFIX + ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveAtomically(temp, target);
        } catch (IOException error) {
            throw new UncheckedIOException("flush of " + table + " failed", error);
        }
        dirtyPageLsn.remove(table);
    }

    private void loadFromDisk() {
        tables.clear();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(tablesDir, "*" + TABLE_SUFFIX)) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String table = fileName.substring(0, fileName.length() - TABLE_SUFFIX.length());
                TreeMap<RID, List<Value>> rows = table(table);
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        rows.put(RecordCodec.decodeRid(table, line), RecordCodec.decodeValues(line));
                    }
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("cannot load tables from " + tablesDir, error);
        }
    }

    private TreeMap<RID, List<Value>> table(String table) {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("invalid table name: " + table);
        }
        return tables.computeIfAbsent(table, name -> new TreeMap<>(RecordCodec.RID_ORDER));
    }

    private static void requireRidOf(String table, RID rid) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(rid, "rid");
        if (!table.equals(rid.pageId().tableId()) || rid.pageId().pageNumber() < 0 || rid.slotId() < 0) {
            throw new IllegalArgumentException("RID " + rid + " does not belong to table " + table);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path entry : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(entry);
            }
        }
    }

    /** Names of tables currently held (including empty ones). */
    public synchronized List<String> tableNames() {
        return new ArrayList<>(new TreeSet<>(tables.keySet()));
    }
}
