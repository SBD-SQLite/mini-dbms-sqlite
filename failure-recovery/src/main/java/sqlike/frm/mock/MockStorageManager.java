package sqlike.frm.mock;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sqlike.frm.port.SmPort;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * File-backed stand-in for Storage Manager so FRM can be tested without the real SM (FR-09).
 *
 * <p>{@code tables} plays the buffer pool; {@link #flushAll} plays writing pages to disk, so data
 * that was never flushed is lost when the process is killed.
 *
 * <p>Milestone 1 MOCK: data lives in memory only; persisting to {@code dataDir} is still TODO.
 */
public class MockStorageManager implements SmPort {

    private static final RecordImage NO_RECORD = new RecordImage(false, List.of());

    private final Path dataDir;
    private final Map<String, Map<RID, RecordImage>> tables = new HashMap<>();
    private int mockSnapshotCount = 0;

    public MockStorageManager(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public synchronized RecordImage readAt(String table, RID rid) {
        return tables.getOrDefault(table, Map.of()).getOrDefault(rid, NO_RECORD);
    }

    @Override
    public synchronized void writeAt(String table, RID rid, RecordImage image, long lsn) {
        Map<RID, RecordImage> rows = tables.computeIfAbsent(table, t -> new HashMap<>());
        if (image.exists()) {
            rows.put(rid, image);
        } else {
            rows.remove(rid);
        }
    }

    @Override
    public synchronized void flushAll() {
        persist();
    }

    @Override
    public synchronized String createSnapshot() {
        // MOCK: TODO(FR1): copy the data files in dataDir into a snapshot directory.
        mockSnapshotCount++;
        return "mock-snapshot-" + mockSnapshotCount;
    }

    @Override
    public synchronized void restoreSnapshot(String snapshotId) {
        // MOCK: no-op. TODO(FR1): replace the data files with the snapshot, then reload tables.
    }

    /** Loads the initial known state of a scenario from a fixture file. */
    public synchronized void loadFixture(Path path) {
        // MOCK: no-op. TODO(FR1): parse the fixture and fill tables, then persist().
    }

    /** Returns a copy of every record in {@code table}, used to compare against expected data. */
    public synchronized Map<RID, RecordImage> dump(String table) {
        return new HashMap<>(tables.getOrDefault(table, Map.of()));
    }

    /** Writes the in-memory tables to {@code dataDir}. */
    private void persist() {
        // MOCK: no-op. TODO(FR1): write tables to dataDir so flushed data survives a killed process.
    }
}
