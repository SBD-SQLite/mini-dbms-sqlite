package sqlike.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** File snapshots. Callers must quiesce mutations and flush before capture. */
public final class SnapshotManager {
    private static final Pattern ID = Pattern.compile("snap-[a-f0-9-]{36}");
    private final Path dataDirectory;
    private final Path catalogFile;
    private final Path root;

    public SnapshotManager(Path dataDirectory, Path catalogFile) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.catalogFile = catalogFile.toAbsolutePath().normalize();
        this.root = this.dataDirectory.resolve("snapshots");
        if (!this.catalogFile.getParent().equals(this.dataDirectory))
            throw new StorageException(StorageErrorCode.INVALID_VALUE, "Catalog must be inside the data directory");
    }

    public String createSnapshot() {
        String id = "snap-" + UUID.randomUUID();
        Path staging = root.resolve(id + ".tmp");
        try {
            Files.createDirectories(staging);
            List<String> manifest = new ArrayList<>();
            for (Path source : activeFiles()) {
                Path target = staging.resolve(source.getFileName());
                Files.copy(source, target);
                force(target);
                manifest.add(source.getFileName() + "|" + Files.size(target) + "|" + digest(target));
            }
            Files.write(staging.resolve("MANIFEST"), manifest);
            force(staging.resolve("MANIFEST"));
            Files.move(staging, root.resolve(id), StandardCopyOption.ATOMIC_MOVE);
            return id;
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Cannot create snapshot", e);
        }
    }

    public void validate(String id) {
        Path directory = directory(id);
        try {
            if (!Files.isDirectory(directory)) throw new IOException("Snapshot does not exist");
            List<String> entries = Files.readAllLines(directory.resolve("MANIFEST"));
            if (entries.isEmpty()) throw new IOException("Empty manifest");
            boolean catalogFound = false;
            Set<String> names = new HashSet<>();
            for (String line : entries) {
                String[] parts = line.split("\\|", -1);
                if (parts.length != 3 || !validName(parts[0]) || !names.add(parts[0])) throw new IOException("Invalid manifest entry");
                Path file = directory.resolve(parts[0]);
                if (Files.size(file) != Long.parseLong(parts[1]) || !digest(file).equals(parts[2]))
                    throw new IOException("Snapshot file mismatch: " + parts[0]);
                catalogFound |= parts[0].equals(catalogFile.getFileName().toString());
            }
            if (!catalogFound) throw new IOException("Snapshot has no catalog");
            try (var actual = Files.list(directory)) {
                if (actual.filter(Files::isRegularFile).count() != names.size() + 1)
                    throw new IOException("Unexpected snapshot files");
            }
        } catch (IOException | NumberFormatException e) {
            throw new StorageException(StorageErrorCode.CORRUPTED_DATA, "Invalid snapshot " + id, e);
        }
    }

    public void restoreSnapshot(String id) {
        validate(id);
        Path source = directory(id);
        try {
            Path backup = root.resolve("restore-" + UUID.randomUUID());
            Files.createDirectories(backup);
            List<Path> active = activeFiles();
            List<Path> moved = new ArrayList<>();
            try {
                for (Path file : active) {
                    Files.move(file, backup.resolve(file.getFileName()));
                    moved.add(file);
                }
            } catch (IOException failure) {
                for (Path file : moved) Files.move(backup.resolve(file.getFileName()), file);
                throw failure;
            }
            try {
                for (String line : Files.readAllLines(source.resolve("MANIFEST"))) {
                    String name = line.split("\\|", -1)[0];
                    Files.copy(source.resolve(name), dataDirectory.resolve(name));
                }
            } catch (IOException failure) {
                for (Path file : activeFiles()) Files.deleteIfExists(file);
                for (Path file : active) Files.move(backup.resolve(file.getFileName()), file);
                throw failure;
            }
            // Keep the previous generation on disk for recovery from a process crash during restore.
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Cannot restore snapshot " + id, e);
        }
    }

    public void restoreTable(String id, String tableId) {
        validate(id);
        if (tableId == null || !tableId.matches("[A-Za-z0-9_-]+"))
            throw new StorageException(StorageErrorCode.INVALID_VALUE, "Invalid table ID");
        Path source = directory(id).resolve(tableId + ".tbl");
        try {
            if (!Files.isRegularFile(source)) throw new IOException("Table absent from snapshot");
            Files.copy(source, dataDirectory.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Cannot restore table " + tableId, e);
        }
    }

    private Path directory(String id) {
        if (id == null || !ID.matcher(id).matches())
            throw new StorageException(StorageErrorCode.INVALID_VALUE, "Invalid snapshot ID");
        return root.resolve(id);
    }

    private List<Path> activeFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.exists(dataDirectory)) return files;
        try (var stream = Files.list(dataDirectory)) {
            stream.filter(Files::isRegularFile).filter(path -> validName(path.getFileName().toString()))
                    .forEach(files::add);
        }
        return files;
    }

    private boolean validName(String name) {
        return name.equals(catalogFile.getFileName().toString())
                || name.matches("[A-Za-z0-9_-]+\\.(tbl|idx)");
    }

    private static String digest(Path file) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) hash.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
