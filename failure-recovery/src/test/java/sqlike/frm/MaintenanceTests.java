package sqlike.frm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import sqlike.frm.checkpoint.CheckpointInfo;
import sqlike.frm.checkpoint.CheckpointLogPort;
import sqlike.frm.checkpoint.CheckpointManager;
import sqlike.frm.checkpoint.SnapshotCatalog;
import sqlike.frm.log.LogType;
import sqlike.frm.maintenance.MaintenanceService;
import sqlike.frm.mock.MockRecoveryLog;
import sqlike.frm.pitr.HistoricalRecoveryPlan;
import sqlike.frm.pitr.PointInTimeRecovery;
import sqlike.frm.port.CcmPort;
import sqlike.frm.port.SmPort;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.RecoveryTargetKind;
import sqlike.shared.Value;

/**
 * Checks for the database maintenance feature: checkpoint (FR-06), point-in-time recovery
 * (FR-05), and {@code MaintenanceService}. Runnable without JUnit: {@code ./run-maintenance-tests.sh}.
 * Exits with status 1 when any check fails.
 *
 * <p>Storage and concurrency control are in-test fakes; {@code MockRecoveryLog} plays the log.
 */
public final class MaintenanceTests {

    private static final String TABLE = "student";
    private static final RID R1 = new RID(new PageId(TABLE, 0), 1);
    private static final RID R2 = new RID(new PageId(TABLE, 0), 2);
    private static final RID R3 = new RID(new PageId(TABLE, 0), 3);
    private static final RID R4 = new RID(new PageId(TABLE, 0), 4);
    private static final RecordImage NONE = new RecordImage(false, List.of());
    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");
    private static final long TIMEOUT_MS = 2_000;

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        run("checkpoint: log durable before flushAll, marker durable, snapshot cataloged",
                MaintenanceTests::checkpointOrder);
        run("checkpoint: timeout aborts before durability/flush/marker and resumes",
                MaintenanceTests::checkpointTimeout);
        run("checkpoint: durability failure stops before flushAll and resumes",
                MaintenanceTests::checkpointDurabilityFailure);
        run("pitr TIME: between two commits keeps only commits at or before target",
                MaintenanceTests::pitrTimeBetweenCommits);
        run("pitr TRANSACTION: restores through that transaction's COMMIT",
                MaintenanceTests::pitrTransactionTarget);
        run("pitr TRANSACTION: uncommitted target fails before touching data",
                MaintenanceTests::pitrUncommittedTarget);
        run("pitr: picks latest snapshot with base LSN <= target",
                MaintenanceTests::snapshotSelection);
        run("pitr: second restore ignores abandoned timeline, keeps new commits",
                MaintenanceTests::secondRestoreIgnoresAbandoned);
        run("pitr: no snapshot before target fails and resumes",
                MaintenanceTests::noSnapshotFails);
        run("snapshot catalog: survives reload from file",
                MaintenanceTests::catalogPersists);
        run("service: restore uses recovery points created through the same service",
                MaintenanceTests::serviceSharesRecoveryPoints);
        run("service: failed periodic checkpoint is reported and schedule keeps running",
                MaintenanceTests::servicePeriodicFailureVisible);
        run("service: gate blocks maintenance while another holder owns it",
                MaintenanceTests::serviceGateSerialises);

        System.out.printf("%n%d passed, %d failed%n", passed, failures.size());
        failures.forEach(f -> System.out.println("  FAIL " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void checkpointOrder() {
        List<String> events = new ArrayList<>();
        Fixture f = new Fixture();
        f.tx(1, () -> f.insert(1, R1, "A"));
        RecordingLog log = new RecordingLog(f.log, events);
        RecordingSm sm = new RecordingSm(f.sm, events);
        CheckpointManager cm = new CheckpointManager(sm, new RecordingCcm(f.ccm, events), log,
                f.catalog, TIMEOUT_MS);

        CheckpointInfo info = cm.checkpoint();

        long last = info.flushedThroughLSN();
        expect(events, List.of("pause", "waitIdle", "ensureDurable(" + last + ")", "flushAll",
                "createSnapshot", "logCheckpoint", "ensureDurable(" + info.checkpointLSN() + ")",
                "resume"), "event order");
        expect(f.log.getDurableLsn(), info.checkpointLSN(), "marker durable");
        expect(f.catalog.snapshots().size(), 1, "catalog entries");
        expect(f.catalog.snapshots().get(0).baseLSN(), last, "snapshot base LSN");
    }

    private static void checkpointTimeout() {
        Fixture f = new Fixture();
        f.ccm.simulateActive(9);
        List<String> events = new ArrayList<>();
        CheckpointManager cm = new CheckpointManager(new RecordingSm(f.sm, events), f.ccm, f.log,
                f.catalog, 50);
        expectThrows(cm::checkpoint, "timeout");
        expect(events.contains("flushAll"), false, "no flush on timeout");
        expect(f.log.getLastLsn(), 0L, "no marker on timeout");
        expect(f.ccm.isPaused(), false, "resumed after timeout");
    }

    private static void checkpointDurabilityFailure() {
        Fixture f = new Fixture();
        f.tx(1, () -> f.insert(1, R1, "A"));
        long before = f.log.getLastLsn();
        f.log.simulateDurabilityFailure(true);
        List<String> events = new ArrayList<>();
        CheckpointManager cm = new CheckpointManager(new RecordingSm(f.sm, events), f.ccm, f.log,
                f.catalog, TIMEOUT_MS);
        expectThrows(cm::checkpoint, "durability failure");
        expect(events.contains("flushAll"), false, "no flush when log not durable");
        expect(f.log.getLastLsn(), before, "no marker written");
        expect(f.catalog.snapshots().size(), 0, "no snapshot cataloged");
        expect(f.ccm.isPaused(), false, "resumed after failure");
    }

    /**
     * T1 and T3 commit before the returned instant. T2 writes before it but commits after it.
     * T4 aborts.
     */
    private static Instant standardHistory(Fixture f) {
        f.checkpoint();
        f.tx(1, () -> f.insert(1, R1, "A"));
        f.log.logBegin(2);
        f.update(2, R1, "A", "B");
        f.tx(3, () -> f.insert(3, R2, "C"));
        Instant between = f.now();
        f.tick();
        f.log.logCommit(2);
        f.tick();
        f.log.logBegin(4);
        f.insert(4, R3, "X");
        f.sm.writeAt(TABLE, R3, NONE, f.log.getLastLsn());
        f.log.logAbort(4);
        f.tick();
        return between;
    }

    private static void pitrTimeBetweenCommits() {
        Fixture f = new Fixture();
        Instant between = standardHistory(f);
        long lastBefore = f.log.getLastLsn();

        HistoricalRecoveryPlan plan = f.pitr.recoverTo(time(between));

        expectData(f.sm, Map.of(R1, row("A"), R2, row("C"), R3, NONE), "data after restore");
        expect(plan.committedTxIds(), Set.of(1L, 3L), "committed set");
        expect(f.ccm.isPaused(), false, "transactions resumed");
        expect(f.ccm.getMaxTxIdAfterRecovery(), 4L, "maxTxId covers whole history");
        expect(f.catalog.isAbandoned(plan.targetLSN() + 1), true, "tail abandoned");
        expect(f.catalog.isAbandoned(lastBefore), true, "last old LSN abandoned");
        expect(f.log.activeLog().size(), 1, "new active log holds only the checkpoint");
        expect(f.log.activeLog().get(0).type(), LogType.CHECKPOINT, "new log starts with checkpoint");
    }

    private static void pitrTransactionTarget() {
        Fixture f = new Fixture();
        standardHistory(f);
        f.pitr.recoverTo(tx(1));
        expectData(f.sm, Map.of(R1, row("A"), R2, NONE, R3, NONE), "only T1 applied");
    }

    private static void pitrUncommittedTarget() {
        Fixture f = new Fixture();
        standardHistory(f);
        Map<RID, RecordImage> before = f.sm.dump(TABLE);
        expectThrows(() -> f.pitr.recoverTo(tx(4)), "aborted target");
        expect(f.sm.dump(TABLE), before, "data unchanged");
        expect(f.ccm.isPaused(), false, "resumed");
    }

    private static void snapshotSelection() {
        Fixture f = new Fixture();
        f.checkpoint();
        f.tx(1, () -> f.insert(1, R1, "A"));
        CheckpointInfo second = f.checkpoint();
        f.tx(2, () -> f.insert(2, R2, "B"));

        HistoricalRecoveryPlan latest = f.pitr.recoverTo(tx(2));
        expect(latest.snapshotId(), second.snapshotId(), "target after 2nd checkpoint uses it");
        expect(latest.changesToReplay().size(), 1, "replays only changes after base");

        Fixture g = new Fixture();
        CheckpointInfo first = g.checkpoint();
        g.tx(1, () -> g.insert(1, R1, "A"));
        g.tx(2, () -> g.insert(2, R2, "B"));
        CheckpointInfo secondG = g.checkpoint();
        g.tx(3, () -> g.insert(3, R3, "C"));
        HistoricalRecoveryPlan early = g.pitr.recoverTo(tx(1));
        expect(early.targetLSN() < secondG.flushedThroughLSN(), true, "target precedes 2nd base");
        expect(early.snapshotId(), first.snapshotId(), "target before 2nd base uses 1st");
        expectData(g.sm, Map.of(R1, row("A"), R2, NONE, R3, NONE), "data at T1");
    }

    private static void secondRestoreIgnoresAbandoned() {
        Fixture f = new Fixture();
        Instant between = standardHistory(f);
        f.pitr.recoverTo(time(between));
        f.tx(5, () -> f.insert(5, R4, "D"));
        f.pitr.recoverTo(time(f.now()));
        expectData(f.sm, Map.of(R1, row("A"), R2, row("C"), R3, NONE, R4, row("D")),
                "abandoned T2 stays out, T5 kept");
        expect(f.ccm.getMaxTxIdAfterRecovery(), 5L, "maxTxId");
    }

    private static void noSnapshotFails() {
        Fixture f = new Fixture();
        f.tx(1, () -> f.insert(1, R1, "A"));
        expectThrows(() -> f.pitr.recoverTo(tx(1)), "no snapshot");
        expect(f.ccm.isPaused(), false, "resumed");
        expectData(f.sm, Map.of(R1, row("A")), "data unchanged");
    }

    private static void catalogPersists() throws Exception {
        Path dir = Files.createTempDirectory("snapshot-catalog");
        Path file = dir.resolve("snapshots.catalog");
        SnapshotCatalog catalog = new SnapshotCatalog(file);
        catalog.recordSnapshot(new CheckpointInfo(5, 4, T0, "s1"));
        catalog.recordAbandoned(10, 20);
        SnapshotCatalog reloaded = new SnapshotCatalog(file);
        expect(reloaded.latestUsableFor(30).orElseThrow().snapshotId(), "s1", "snapshot reloaded");
        expect(reloaded.isAbandoned(15), true, "abandoned range reloaded");
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
    }

    private static MaintenanceService service(Fixture f, Duration interval) {
        return new MaintenanceService(f.sm, f.ccm, f.log, f.log, null, interval, TIMEOUT_MS);
    }

    private static void serviceSharesRecoveryPoints() {
        Fixture f = new Fixture();
        try (MaintenanceService maintenance = service(f, Duration.ZERO)) {
            CheckpointInfo point = maintenance.checkpoint();
            f.tick();
            f.tx(1, () -> f.insert(1, R1, "A"));
            f.tx(2, () -> f.insert(2, R2, "B"));
            HistoricalRecoveryPlan plan = maintenance.recoverTo(tx(1));
            expect(plan.snapshotId(), point.snapshotId(), "restore starts from service checkpoint");
            expectData(f.sm, Map.of(R1, row("A"), R2, NONE), "data at T1");
            expect(maintenance.latestRecoveryPoint().isPresent(), true, "new recovery point exists");
            expect(maintenance.latestRecoveryPoint().orElseThrow().snapshotId().equals(point.snapshotId()),
                    false, "latest point is the post-restore checkpoint");
        }
    }

    private static void servicePeriodicFailureVisible() throws Exception {
        Fixture f = new Fixture();
        f.ccm.simulateActive(7);
        try (MaintenanceService maintenance = new MaintenanceService(
                f.sm, f.ccm, f.log, f.log, null, Duration.ofMillis(30), 20)) {
            maintenance.startPeriodicCheckpoints();
            long deadline = System.currentTimeMillis() + 2_000;
            while (maintenance.lastPeriodicFailure().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            expect(maintenance.lastPeriodicFailure().isPresent(), true, "failure reported");
            expect(maintenance.isPeriodicCheckpointRunning(), true, "schedule still running");
            expect(f.ccm.isPaused(), false, "transactions reopened after failed run");
        }
    }

    private static void serviceGateSerialises() throws Exception {
        Fixture f = new Fixture();
        try (MaintenanceService maintenance = service(f, Duration.ZERO)) {
            maintenance.maintenanceGate().lock();
            Thread other = new Thread(maintenance::checkpoint);
            try {
                other.start();
                Thread.sleep(100);
                expect(f.log.getLastLsn(), 0L, "checkpoint waits for the gate");
            } finally {
                maintenance.maintenanceGate().unlock();
            }
            other.join(2_000);
            expect(maintenance.latestRecoveryPoint().isPresent(), true, "checkpoint ran after release");
        }
    }

    private static final class Fixture {
        final AtomicReference<Instant> clock;
        final MockRecoveryLog log;
        final FakeSm sm;
        final FakeCcm ccm;
        final SnapshotCatalog catalog;
        final CheckpointManager checkpoints;
        final PointInTimeRecovery pitr;

        Fixture() {
            this.clock = new AtomicReference<>(T0);
            this.log = new MockRecoveryLog(clock::get);
            this.sm = new FakeSm();
            this.ccm = new FakeCcm();
            this.catalog = SnapshotCatalog.inMemory();
            this.checkpoints = new CheckpointManager(sm, ccm, log, catalog, TIMEOUT_MS);
            this.pitr = new PointInTimeRecovery(sm, ccm, log, catalog, checkpoints, TIMEOUT_MS);
        }

        Instant now() {
            return clock.get();
        }

        void tick() {
            clock.set(clock.get().plusSeconds(1));
        }

        CheckpointInfo checkpoint() {
            CheckpointInfo info = checkpoints.checkpoint();
            tick();
            return info;
        }

        void tx(long txId, Runnable body) {
            log.logBegin(txId);
            body.run();
            log.logCommit(txId);
            tick();
        }

        void insert(long txId, RID rid, String value) {
            change(txId, ChangeOperation.INSERT, rid, NONE, row(value));
        }

        void update(long txId, RID rid, String from, String to) {
            change(txId, ChangeOperation.UPDATE, rid, row(from), row(to));
        }

        private void change(long txId, ChangeOperation op, RID rid, RecordImage before, RecordImage after) {
            long lsn = log.logChange(txId, new DataChange(op, TABLE, rid, before, after));
            sm.writeAt(TABLE, rid, after, lsn);
        }
    }

    /** In-memory SM: tables play the disk, snapshots are deep copies. */
    private static final class FakeSm implements SmPort {
        private final Map<String, Map<RID, RecordImage>> tables = new HashMap<>();
        private final Map<String, Map<String, Map<RID, RecordImage>>> snapshots = new HashMap<>();
        private int count;

        public RecordImage readAt(String table, RID rid) {
            return tables.getOrDefault(table, Map.of()).getOrDefault(rid, NONE);
        }

        public void writeAt(String table, RID rid, RecordImage image, long lsn) {
            Map<RID, RecordImage> rows = tables.computeIfAbsent(table, t -> new HashMap<>());
            if (image.exists()) {
                rows.put(rid, image);
            } else {
                rows.remove(rid);
            }
        }

        public void flushAll() {
        }

        public String createSnapshot() {
            String id = "snap-" + (++count);
            snapshots.put(id, copy(tables));
            return id;
        }

        public void restoreSnapshot(String snapshotId) {
            Map<String, Map<RID, RecordImage>> snapshot = snapshots.get(snapshotId);
            if (snapshot == null) {
                throw new IllegalArgumentException("unknown snapshot " + snapshotId);
            }
            tables.clear();
            tables.putAll(copy(snapshot));
        }

        Map<RID, RecordImage> dump(String table) {
            return new HashMap<>(tables.getOrDefault(table, Map.of()));
        }

        private static Map<String, Map<RID, RecordImage>> copy(Map<String, Map<RID, RecordImage>> source) {
            Map<String, Map<RID, RecordImage>> result = new HashMap<>();
            source.forEach((table, rows) -> result.put(table, new HashMap<>(rows)));
            return result;
        }
    }

    /** CCM gate fake: tests mark transactions active by hand. */
    private static final class FakeCcm implements CcmPort {
        private final Set<Long> active = new HashSet<>();
        private boolean paused;
        private long maxTxIdAfterRecovery;

        public synchronized void pauseNewTransactions() {
            paused = true;
        }

        public synchronized boolean waitUntilIdle(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!active.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        public synchronized void resumeTransactions() {
            paused = false;
        }

        public synchronized void initAfterRecovery(long maxTxId) {
            active.clear();
            maxTxIdAfterRecovery = maxTxId;
        }

        synchronized void simulateActive(long txId) {
            active.add(txId);
        }

        synchronized boolean isPaused() {
            return paused;
        }

        synchronized long getMaxTxIdAfterRecovery() {
            return maxTxIdAfterRecovery;
        }
    }

    private record RecordingLog(CheckpointLogPort inner, List<String> events) implements CheckpointLogPort {
        public long getLastLsn() {
            return inner.getLastLsn();
        }

        public void ensureDurable(long lsn) {
            events.add("ensureDurable(" + lsn + ")");
            inner.ensureDurable(lsn);
        }

        public CheckpointInfo logCheckpoint(long flushed, String snapshotId) {
            events.add("logCheckpoint");
            return inner.logCheckpoint(flushed, snapshotId);
        }
    }

    private record RecordingSm(SmPort inner, List<String> events) implements SmPort {
        public RecordImage readAt(String table, RID rid) {
            return inner.readAt(table, rid);
        }

        public void writeAt(String table, RID rid, RecordImage image, long lsn) {
            inner.writeAt(table, rid, image, lsn);
        }

        public void flushAll() {
            events.add("flushAll");
            inner.flushAll();
        }

        public String createSnapshot() {
            events.add("createSnapshot");
            return inner.createSnapshot();
        }

        public void restoreSnapshot(String snapshotId) {
            events.add("restoreSnapshot");
            inner.restoreSnapshot(snapshotId);
        }
    }

    private record RecordingCcm(CcmPort inner, List<String> events) implements CcmPort {
        public void pauseNewTransactions() {
            events.add("pause");
            inner.pauseNewTransactions();
        }

        public boolean waitUntilIdle(long timeoutMs) {
            events.add("waitIdle");
            return inner.waitUntilIdle(timeoutMs);
        }

        public void resumeTransactions() {
            events.add("resume");
            inner.resumeTransactions();
        }

        public void initAfterRecovery(long maxTxId) {
            events.add("init");
            inner.initAfterRecovery(maxTxId);
        }
    }

    private static RecordImage row(String value) {
        return new RecordImage(true, List.of(new Value(DataType.VARCHAR, Optional.empty(),
                Optional.empty(), Optional.of(value))));
    }

    private static RecoveryTarget time(Instant instant) {
        return new RecoveryTarget(RecoveryTargetKind.TIME, Optional.of(instant), Optional.empty());
    }

    private static RecoveryTarget tx(long txId) {
        return new RecoveryTarget(RecoveryTargetKind.TRANSACTION, Optional.empty(), Optional.of(txId));
    }

    private static void expectData(SmPort sm, Map<RID, RecordImage> expected, String label) {
        for (Map.Entry<RID, RecordImage> entry : expected.entrySet()) {
            expect(sm.readAt(TABLE, entry.getKey()), entry.getValue(), label + " @" + entry.getKey());
        }
    }

    private static void expect(Object actual, Object expected, String label) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static void expectThrows(Runnable action, String label) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(label + ": expected an exception");
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void run(String name, Check check) {
        try {
            check.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable error) {
            failures.add(name + " -> " + error.getMessage());
            System.out.println("[FAIL] " + name + " -> " + error.getMessage());
        }
    }
}
