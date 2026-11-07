package sqlike.ccm;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.ResourceId;
import sqlike.shared.Value;

/** Small assertion helper shared by the standalone CCM test classes. */
final class Check {

    private final String suite;
    private int passed;

    Check(String suite) {
        this.suite = suite;
    }

    void test(String name, Body body) throws Exception {
        body.run();
        passed++;
        System.out.println("PASS: " + name);
    }

    void done() {
        System.out.println(suite + ": " + passed + " tests passed");
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void rejects(Class<? extends Exception> type, Body body) throws Exception {
        try {
            body.run();
        } catch (Exception e) {
            if (type.isInstance(e)) {
                return;
            }
            throw e;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    static <T> T get(Future<T> future) throws Exception {
        return future.get(2, TimeUnit.SECONDS);
    }

    static void expectBlocked(Future<?> future) throws Exception {
        try {
            future.get(200, TimeUnit.MILLISECONDS);
            throw new AssertionError("call returned but should still be waiting");
        } catch (TimeoutException expected) {
            // still waiting, as required
        }
    }

    static ResourceId record(String table, int slot) {
        return ResourceId.ofRecord(table, new RID(new PageId(table, 0L), slot));
    }

    static DataChange change(String table, int slot) {
        RID rid = new RID(new PageId(table, 0L), slot);
        return new DataChange(ChangeOperation.UPDATE, table, rid, image(1), image(2));
    }

    private static RecordImage image(int value) {
        return new RecordImage(true, List.of(
                new Value(DataType.INTEGER, Optional.of(value), Optional.empty(), Optional.empty())));
    }

    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }
}
