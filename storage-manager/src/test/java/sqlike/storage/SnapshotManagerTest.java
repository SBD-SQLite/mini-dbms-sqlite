package sqlike.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class SnapshotManagerTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sm-snapshot-test-");
        Path data = Files.createDirectory(root.resolve("data"));
        Path catalog = data.resolve("catalog.bin");
        Path table = data.resolve("tbl_people.tbl");
        
        Files.write(catalog, new byte[]{1, 2});
        Files.write(table, new byte[]{3, 4});
        
        SnapshotManager snapshots = new SnapshotManager(data, catalog);
        
        String id = snapshots.createSnapshot();
        if (id.isBlank()) throw new AssertionError("Missing snapshot ID");

        Files.write(table, new byte[]{9});
        Files.write(data.resolve("tbl_new.tbl"), new byte[]{8});
        snapshots.restoreSnapshot(id);
        check(table, new byte[]{3, 4});
        if (Files.exists(data.resolve("tbl_new.tbl"))) throw new AssertionError("New table survived restore");
        snapshots.restoreSnapshot(id);
        check(table, new byte[]{3, 4});
        Files.write(table, new byte[]{7});
        try {
            snapshots.restoreSnapshot("missing");
            throw new AssertionError("Unknown snapshot accepted");
        } catch (StorageException expected) {
            check(table, new byte[]{7});
        }
        snapshots.restoreTable(id, "tbl_people");
        check(table, new byte[]{3, 4});
        Files.write(data.resolve("tbl_other.tbl"), new byte[]{6});
        Files.write(table, new byte[]{5});
        snapshots.restoreTable(id, "tbl_people");
        check(data.resolve("tbl_other.tbl"), new byte[]{6});
        check(table, new byte[]{3, 4});
        Path captured = data.resolve("snapshots").resolve(id).resolve("tbl_people.tbl");
        Files.write(captured, new byte[]{3, 5});
        try {
            snapshots.restoreSnapshot(id);
            throw new AssertionError("Corrupt snapshot accepted");
        } catch (StorageException expected) {
            check(table, new byte[]{3, 4});
        }
        System.out.println("SnapshotManagerTest passed");
    }

    private static void check(Path file, byte[] expected) throws Exception {
        if (!Arrays.equals(Files.readAllBytes(file), expected)) throw new AssertionError(file + " differs");
    }
}
