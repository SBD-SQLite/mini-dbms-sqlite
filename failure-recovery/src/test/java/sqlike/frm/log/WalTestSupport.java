package sqlike.frm.log;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import sqlike.shared.*;

final class WalTestSupport {
    static final Instant TIME = Instant.parse("2026-11-14T10:00:00Z");
    static final RID RID = new RID(new PageId("account", 3), 2);
    static final RecordImage ABSENT = new RecordImage(false, List.of());
    static int passed;
    static int failed;

    interface Test {
        void run() throws Exception;
    }

    static DataChange change(ChangeOperation op, RecordImage before, RecordImage after) {
        return new DataChange(op, "account", RID, before, after);
    }

    static RecordImage row(int n) {
        Value value = new Value(DataType.INTEGER, Optional.of(n), Optional.empty(), Optional.empty());
        return new RecordImage(true, List.of(value));
    }

    static void run(String name, Test test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Exception | AssertionError e) {
            failed++;
            System.out.println("[FAIL] " + name);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    static void eq(Object expected, Object actual) {
        check(Objects.equals(expected, actual), "expected=" + expected + ", actual=" + actual);
    }

    static <T extends Exception> T expect(Class<T> type, Test test) throws Exception {
        try {
            test.run();
        } catch (Exception e) {
            if (type.isInstance(e)) {
                return type.cast(e);
            }
            throw e;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    static final class TestLog implements AutoCloseable {
        final Path dir = Files.createTempDirectory("sqlike-log-test-");
        final Path path = dir.resolve("wal.log");
        LogManager wal = new LogManager(path.toString());

        TestLog() throws IOException {
        }

        void reopen() {
            wal.close();
            wal = new LogManager(path.toString());
        }

        @Override
        public void close() throws Exception {
            try {
                wal.close();
            } finally {
                try (var files = Files.walk(dir)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }
}
