package sqlike.frm.checkpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pengelola berkas append-only `snapshots.catalog` untuk mencatat snapshot aktif
 * dan rentang LSN yang dibuang (ABANDON) akibat PITR.
 */
public class SnapshotCatalog {

    private final Path catalogPath;
    private final List<SnapshotEntry> snapshots = new ArrayList<>();
    private final List<long[]> abandonedRanges = new ArrayList<>();

    public SnapshotCatalog(Path catalogPath) {
        this.catalogPath = catalogPath;
        if (catalogPath != null && Files.exists(catalogPath)) {
            loadCatalog();
        }
    }

    private synchronized void loadCatalog() {
        snapshots.clear();
        abandonedRanges.clear();
        try {
            List<String> lines = Files.readAllLines(catalogPath);
            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|");
                if ("SNAPSHOT".equals(parts[0])) {
                    snapshots.add(new SnapshotEntry(
                            parts[1],
                            Long.parseLong(parts[2]),
                            Long.parseLong(parts[3]),
                            Instant.ofEpochMilli(Long.parseLong(parts[4]))
                    ));
                } else if ("ABANDON".equals(parts[0])) {
                    abandonedRanges.add(new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])});
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Gagal membaca berkas katalog: " + catalogPath, e);
        }
    }

    public synchronized void recordSnapshot(SnapshotEntry entry) {
        snapshots.add(entry);
        appendLine("SNAPSHOT|" + entry.snapshotId() + "|" + entry.baseLSN() + "|" 
                   + entry.checkpointLSN() + "|" + entry.createdAt().toEpochMilli());
    }

    public synchronized void recordAbandon(long fromLSN, long toLSN) {
        abandonedRanges.add(new long[]{fromLSN, toLSN});
        appendLine("ABANDON|" + fromLSN + "|" + toLSN);
    }

    public synchronized Optional<SnapshotEntry> latestValidSnapshot() {
        if (snapshots.isEmpty()) return Optional.empty();

        for (int i = snapshots.size() - 1; i >= 0; i--) {
            SnapshotEntry entry = snapshots.get(i);
            if (!isAbandoned(entry.baseLSN())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<SnapshotEntry> findBaseSnapshotFor(long targetLSN) {
        SnapshotEntry bestMatch = null;
        for (SnapshotEntry entry : snapshots) {
            if (entry.baseLSN() <= targetLSN && !isAbandoned(entry.baseLSN())) {
                if (bestMatch == null || entry.baseLSN() > bestMatch.baseLSN()) {
                    bestMatch = entry;
                }
            }
        }
        return Optional.ofNullable(bestMatch);
    }

    private boolean isAbandoned(long lsn) {
        for (long[] range : abandonedRanges) {
            if (lsn >= range[0] && lsn <= range[1]) return true;
        }
        return false;
    }

    private void appendLine(String line) {
        if (catalogPath == null) return; // In-memory mode (untuk testing)
        try {
            if (catalogPath.getParent() != null && !Files.exists(catalogPath.getParent())) {
                Files.createDirectories(catalogPath.getParent());
            }
            Files.writeString(catalogPath, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new IllegalStateException("Gagal menulis entri ke snapshot catalog", e);
        }
    }
}