package sqlike.frm.driver;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sqlike.contract.IFailureRecoveryManager;
import sqlike.frm.LogViewer;
import sqlike.frm.log.LogRecord;
import sqlike.frm.mock.MockCCM;
import sqlike.frm.mock.MockStorageManager;
import sqlike.frm.mock.RecordCodec;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryResult;
import sqlike.shared.Value;

/**
 * One "process" worth of components for a scenario: mock SM, mock CCM, and the FRM under test,
 * wired like the real bootstrap. Transaction helpers follow the PRD §10 order that QP and CCM
 * use, so scenarios read like the real write path:
 *
 * <pre>
 * begin:  CCM starts tx  -> FRM.logBegin
 * write:  read before-image from SM -> FRM.logChange -> SM.writeAt(after, lsn)
 * commit: FRM.logCommit (durable) -> CCM finishes tx
 * abort:  FRM.rollback -> FRM.logAbort (CCM owns ABORT) -> CCM finishes tx
 * </pre>
 */
public final class ScenarioContext implements AutoCloseable {

    /** Line a child process prints when it reaches its crash point. */
    public static final String CRASH_MARKER = "FRM-CRASH-POINT";
    /** Line prefix a child process prints for a failed check. */
    public static final String CHECK_FAILED = "CHECK-FAILED";

    private final Path dataDir;
    private final MockStorageManager sm;
    private final MockCCM ccm;
    private final IFailureRecoveryManager frm;
    private final boolean childProcess;
    private final List<String> failedChecks = new ArrayList<>();
    private final PrintStream out;

    private ScenarioContext(Path dataDir, FrmFactory factory, Duration idleTimeout, boolean childProcess) {
        this.dataDir = dataDir;
        this.sm = new MockStorageManager(dataDir);
        this.ccm = new MockCCM();
        this.frm = factory.create(sm, ccm, dataDir, idleTimeout);
        this.sm.setWal(frm::ensureDurable);
        this.childProcess = childProcess;
        this.out = System.out;
    }

    /** Opens components on {@code dataDir} inside the driver process. */
    public static ScenarioContext open(Path dataDir, FrmFactory factory, Duration idleTimeout) {
        return new ScenarioContext(dataDir, factory, idleTimeout, false);
    }

    /** Opens components inside a child process started by {@link CrashSimulator}. */
    static ScenarioContext openInChild(Path dataDir, FrmFactory factory, Duration idleTimeout) {
        return new ScenarioContext(dataDir, factory, idleTimeout, true);
    }

    // ------------------------------------------------------------------ startup

    /** What QP does at startup: FRM.recover, then CCM.initAfterRecovery with lastTxId (PRD §14). */
    public RecoveryResult startup() {
        RecoveryResult result = frm.recover();
        ccm.initAfterRecovery(result.lastTxId());
        return result;
    }

    // ------------------------------------------------------------------ transactions

    public long begin(long txId) {
        ccm.simulateActive(txId);
        return frm.logBegin(txId);
    }

    public long insert(long txId, RID rid, Value... values) {
        return write(txId, ChangeOperation.INSERT, rid, new RecordImage(true, List.of(values)));
    }

    public long update(long txId, RID rid, Value... values) {
        return write(txId, ChangeOperation.UPDATE, rid, new RecordImage(true, List.of(values)));
    }

    public long delete(long txId, RID rid) {
        return write(txId, ChangeOperation.DELETE, rid, new RecordImage(false, List.of()));
    }

    public void commit(long txId) {
        frm.logCommit(txId);
        ccm.simulateFinish(txId);
    }

    /** CCM abort path: rollback first, then the final ABORT, then release. */
    public void abort(long txId) {
        frm.rollback(txId);
        frm.logAbort(txId);
        ccm.simulateFinish(txId);
    }

    private long write(long txId, ChangeOperation operation, RID rid, RecordImage after) {
        String table = rid.pageId().tableId();
        RecordImage before = sm.readAt(table, rid);
        long lsn = frm.logChange(txId, new DataChange(operation, table, rid, before, after));
        if (lsn <= 0) {
            throw new IllegalStateException("logChange returned a non-positive LSN: " + lsn);
        }
        sm.writeAt(table, rid, after, lsn);
        return lsn;
    }

    // ------------------------------------------------------------------ crash and checks

    /**
     * Child process: announces the crash point and blocks until the driver kills it with
     * {@code destroyForcibly()}, so nothing is flushed by a graceful shutdown.
     */
    public void crash(CrashPoint point) {
        if (!childProcess) {
            throw new IllegalStateException("crash() is only valid in a child process");
        }
        out.println(CRASH_MARKER + "\t" + point);
        out.flush();
        while (true) {
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException ignored) {
                // keep waiting for the kill
            }
        }
    }

    /** Records a failed expectation without stopping the scenario. */
    public void check(boolean condition, String description) {
        if (!condition) {
            failedChecks.add(description);
            if (childProcess) {
                out.println(CHECK_FAILED + "\t" + description);
                out.flush();
            }
        }
    }

    /** Runs {@code action} and checks that it throws; returns the error for further checks. */
    public Optional<RuntimeException> expectFailure(String description, Runnable action) {
        try {
            action.run();
            check(false, description + " (no error was thrown)");
            return Optional.empty();
        } catch (RuntimeException expected) {
            return Optional.of(expected);
        }
    }

    public List<String> failedChecks() {
        return List.copyOf(failedChecks);
    }

    // ------------------------------------------------------------------ access

    public List<LogRecord> readLogs(Optional<Long> txId) {
        if (frm instanceof LogViewer viewer) {
            return viewer.readLogs(txId);
        }
        throw new UnsupportedOperationException("FRM under test does not implement LogViewer");
    }

    public String showLogs(Optional<Long> txId) {
        if (frm instanceof LogViewer viewer) {
            return viewer.showLogs(txId);
        }
        throw new UnsupportedOperationException("FRM under test does not implement LogViewer");
    }

    public IFailureRecoveryManager frm() {
        return frm;
    }

    public MockStorageManager sm() {
        return sm;
    }

    public MockCCM ccm() {
        return ccm;
    }

    public Path dataDir() {
        return dataDir;
    }

    public String describe(RID rid) {
        RecordImage image = sm.readAt(rid.pageId().tableId(), rid);
        return RecordCodec.show(rid) + "=" + (image.exists() ? RecordCodec.show(image.values()) : "absent");
    }

    @Override
    public void close() {
        if (frm instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception error) {
                throw new IllegalStateException("closing FRM failed", error);
            }
        }
    }
}
