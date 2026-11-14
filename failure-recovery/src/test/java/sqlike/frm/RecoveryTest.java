package sqlike.frm;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.mock.MockCCM;
import sqlike.frm.mock.MockStorageManager;
import sqlike.frm.mock.RecordCodec;
import sqlike.frm.recovery.RecoveryAnalyzer;
import sqlike.frm.recovery.RecoveryManager;
import sqlike.frm.recovery.RecoveryPlan;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.ErrorCode;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.RecoveryResult;
import sqlike.shared.Value;

public class RecoveryTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("AC-1 startup gate", RecoveryTest::testAC1_StartupGate);
        run("AC-2 checkpoint and no-checkpoint recovery", RecoveryTest::testAC2_CheckpointRecovery);
        run("AC-3 transaction outcomes and AC-4 redo/undo",
                RecoveryTest::testAC3_TransactionStatusAndAC4_RedoUndo);
        run("AC-5 INSERT/UPDATE/DELETE and repeated RID changes",
                RecoveryTest::testAC5_OperationsAndRepeatedChanges);
        run("AC-6 no-force and steal data states", RecoveryTest::testAC6_DataCorrectnessOnDisk);
        run("AC-7 torn tail and middle corruption", RecoveryTest::testAC7_LogDamageHandling);
        run("AC-8 repeated restart idempotence", RecoveryTest::testAC8_IdempotentRestart);
        run("recovery retry after interrupted undo", RecoveryTest::testInterruptedUndoRetry);
        run("AC-9 fail-stop after recovery failure", RecoveryTest::testAC9_FailStopOnError);
        run("WAL failure keeps WAL_FAILURE code", RecoveryTest::testWalFailureCode);

        System.out.printf("%n=== HASIL AKHIR: %d PASSED, %d FAILED ===%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void run(String name, TestCase test) {
        try {
            test.run();
        } catch (Exception | AssertionError error) {
            check(false, name, "unexpected exception: " + describe(error));
        }
    }

    private static void check(boolean condition, String testName, String message) {
        if (condition) {
            System.out.println("  [PASS] " + testName + ": " + message);
            passed++;
        } else {
            System.out.println("  [FAIL] " + testName + ": " + message);
            failed++;
        }
    }

    private static Path createTempDir() throws Exception {
        return Files.createTempDirectory("frm-ac-test-");
    }

    private static void cleanup(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception error) {
            System.err.println("Cleanup failed for " + dir + ": " + error.getMessage());
        }
    }

    private static FailureRecoveryManager open(
            MockStorageManager sm, MockCCM ccm, Path walPath) {
        FailureRecoveryManager frm =
                new FailureRecoveryManager(sm, ccm, walPath.toString());
        sm.setWal(frm::ensureDurable);
        return frm;
    }

    private static void close(FailureRecoveryManager frm) {
        if (frm != null) {
            frm.close();
        }
    }

    private static RecordImage image(int value) {
        return new RecordImage(true, List.of(RecordCodec.integer(value)));
    }

    private static RecordImage absent() {
        return new RecordImage(false, List.of());
    }

    private static DataChange insert(RID rid, int value) {
        return new DataChange(ChangeOperation.INSERT, "account", rid, absent(), image(value));
    }

    private static DataChange update(RID rid, int before, int after) {
        return new DataChange(ChangeOperation.UPDATE, "account", rid, image(before), image(after));
    }

    private static DataChange delete(RID rid, int before) {
        return new DataChange(ChangeOperation.DELETE, "account", rid, image(before), absent());
    }

    private static long logAndApply(
            FailureRecoveryManager frm, MockStorageManager sm, long txId, DataChange change) {
        long lsn = frm.logChange(txId, change);
        sm.writeAt(change.tableName(), change.rid(), change.after(), lsn);
        return lsn;
    }

    private static Integer integerAt(MockStorageManager sm, RID rid) {
        RecordImage image = sm.readAt("account", rid);
        if (!image.exists() || image.values().isEmpty()) {
            return null;
        }
        return image.values().get(0).integerValue().orElseThrow();
    }

    private static List<LogRecord> recordsFor(List<LogRecord> records, long txId) {
        return records.stream()
                .filter(record -> record.txID().isPresent() && record.txID().get() == txId)
                .toList();
    }

    private static long countType(List<LogRecord> records, long txId, LogType type) {
        return recordsFor(records, txId).stream().filter(record -> record.type() == type).count();
    }

    private static LogRecord latestCheckpoint(List<LogRecord> records) {
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).type() == LogType.CHECKPOINT) {
                return records.get(i);
            }
        }
        throw new AssertionError("No checkpoint record in WAL");
    }

    private static boolean rejectedAsNotReady(ThrowingAction action) {
        try {
            action.run();
            return false;
        } catch (FrmException error) {
            return error.getCode() == ErrorCode.INVALID_STATE;
        } catch (Exception error) {
            return false;
        }
    }

    private static String describe(Throwable error) {
        StringBuilder message = new StringBuilder(error.getClass().getSimpleName());
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause != error) {
                message.append(": ").append(cause.getMessage());
            }
        }
        return message.toString();
    }

    private static boolean messageChainContains(Throwable error, String expected) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null
                    && cause.getMessage().toLowerCase().contains(expected.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static void testAC1_StartupGate() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        try {
            MockStorageManager sm = new MockStorageManager(dir);
            MockCCM ccm = new MockCCM();
            frm = open(sm, ccm, dir.resolve("wal.log"));

            RID rid = new RID(new PageId("account", 0), 0);
            DataChange change = insert(rid, 1);
            FailureRecoveryManager manager = frm;
            check(frm.getState() == RecoveryState.NOT_STARTED,
                    "AC-1 initial state", "FRM starts in NOT_STARTED");
            check(rejectedAsNotReady(() -> manager.logBegin(1)),
                    "AC-1 BEGIN gate", "logBegin is rejected before recovery");
            check(rejectedAsNotReady(() -> manager.logChange(1, change)),
                    "AC-1 change gate", "logChange is rejected before recovery");
            check(rejectedAsNotReady(() -> manager.logCommit(1)),
                    "AC-1 COMMIT gate", "logCommit is rejected before recovery");
            check(rejectedAsNotReady(frm::checkpoint),
                    "AC-1 checkpoint gate", "checkpoint is rejected before recovery");

            RecoveryResult result = frm.recover();
            check(frm.getState() == RecoveryState.READY && result != null,
                    "AC-1 recovery completion", "FRM becomes READY only after recovery succeeds");
            boolean beginAccepted = true;
            try {
                frm.logBegin(1);
            } catch (FrmException error) {
                beginAccepted = false;
            }
            check(beginAccepted, "AC-1 post-recovery gate", "logBegin is accepted after recovery");
        } finally {
            close(frm);
            cleanup(dir);
        }
    }

    private static void testAC2_CheckpointRecovery() throws Exception {
        testNoCheckpointRecoveryStartsAtBeginning();

        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        FailureRecoveryManager restarted = null;
        try {
            MockStorageManager sm = new MockStorageManager(dir);
            frm = open(sm, new MockCCM(), dir.resolve("wal.log"));
            frm.recover();

            RID beforeCheckpoint = new RID(new PageId("account", 0), 0);
            frm.logBegin(1);
            logAndApply(frm, sm, 1, insert(beforeCheckpoint, 10));
            frm.logCommit(1);
            frm.checkpoint();

            List<LogRecord> afterCheckpoint = frm.readLogs(Optional.empty());
            LogRecord checkpoint = latestCheckpoint(afterCheckpoint);
            long checkpointLsn = checkpoint.lsn();
            long scanFrom = checkpoint.checkpoint().orElseThrow().flushedThroughLSN() + 1;
            RecoveryPlan plan = new RecoveryAnalyzer().analyze(afterCheckpoint);
            check(plan.checkpointLsn != null && plan.checkpointLsn == checkpointLsn,
                    "AC-2 latest checkpoint", "analyzer selects the latest completed checkpoint");
            check(plan.startLsn == scanFrom,
                    "AC-2 checkpoint boundary", "analysis starts after flushedThroughLSN");

            RID committedAfterCheckpoint = new RID(new PageId("account", 0), 1);
            frm.logBegin(2);
            logAndApply(frm, sm, 2, insert(committedAfterCheckpoint, 20));
            frm.logCommit(2);

            RID activeAfterCheckpoint = new RID(new PageId("account", 0), 2);
            frm.logBegin(3);
            logAndApply(frm, sm, 3, insert(activeAfterCheckpoint, 30));
            sm.flushAll();

            close(frm);
            frm = null;

            MockStorageManager sm2 = new MockStorageManager(dir);
            restarted = open(sm2, new MockCCM(), dir.resolve("wal.log"));
            RecoveryResult result = restarted.recover();
            check(result.checkpointLSN().orElse(-1L) == checkpointLsn,
                    "AC-2 recovery result", "restart reports the selected latest checkpoint");
            check(integerAt(sm2, beforeCheckpoint) == 10
                            && integerAt(sm2, committedAfterCheckpoint) == 20
                            && integerAt(sm2, activeAfterCheckpoint) == null,
                    "AC-2 checkpoint recovery data",
                    "checkpoint data remains, later committed data is redone, active data is undone");
        } finally {
            close(restarted);
            close(frm);
            cleanup(dir);
        }
    }

    private static void testNoCheckpointRecoveryStartsAtBeginning() throws Exception {
        Path dir = createTempDir();
        LogManager writer = null;
        LogManager recoveryLog = null;
        try {
            Path walPath = dir.resolve("wal.log");
            RID rid = new RID(new PageId("account", 0), 0);

            writer = new LogManager(walPath.toString());
            writer.logBegin(1);
            long changeLsn = writer.logChange(1, insert(rid, 41));
            writer.logCommit(1);
            RecoveryPlan plan = new RecoveryAnalyzer().analyze(writer.readLogs(null));
            check(plan.checkpointLsn == null && plan.startLsn == 1,
                    "AC-2 no-checkpoint analysis", "without a checkpoint, scanning starts at LSN 1");
            writer.close();
            writer = null;

            MockStorageManager sm = new MockStorageManager(dir);
            recoveryLog = new LogManager(walPath.toString());
            sm.setWal(recoveryLog::ensureDurable);
            RecoveryResult result = new RecoveryManager(recoveryLog, sm).recover();
            check(result.checkpointLSN().isEmpty(),
                    "AC-2 no-checkpoint result", "no checkpoint is reported when none exists");
            check(integerAt(sm, rid) == 41 && result.lastRecoveredLSN() >= changeLsn,
                    "AC-2 no-checkpoint redo", "a committed change from the beginning of the log is redone");
        } finally {
            if (recoveryLog != null) {
                recoveryLog.close();
            }
            if (writer != null) {
                writer.close();
            }
            cleanup(dir);
        }
    }

    private static void testAC3_TransactionStatusAndAC4_RedoUndo() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        FailureRecoveryManager restarted = null;
        try {
            MockStorageManager sm = new MockStorageManager(dir);
            frm = open(sm, new MockCCM(), dir.resolve("wal.log"));
            frm.recover();

            RID committedRid = new RID(new PageId("account", 0), 0);
            frm.logBegin(1);
            frm.logChange(1, insert(committedRid, 1));
            frm.logCommit(1);

            RID activeRid = new RID(new PageId("account", 0), 1);
            frm.logBegin(2);
            logAndApply(frm, sm, 2, insert(activeRid, 2));
            sm.flushAll();

            RID abortedRid = new RID(new PageId("account", 0), 2);
            frm.logBegin(3);
            logAndApply(frm, sm, 3, insert(abortedRid, 3));
            sm.flushAll();
            frm.rollback(3);
            frm.logAbort(3);

            List<LogRecord> beforeCrash = frm.readLogs(Optional.empty());
            RecoveryPlan plan = new RecoveryAnalyzer().analyze(beforeCrash);
            check(plan.committedTx.contains(1L),
                    "AC-3 committed state", "COMMIT record classifies T1 as committed");
            check(plan.uncommittedTx.contains(2L)
                            && plan.needsAbortTx.contains(2L)
                            && !plan.committedTx.contains(2L),
                    "AC-3 unfinished state", "T2 has no terminal record and needs recovery abort");
            check(countType(beforeCrash, 3, LogType.ABORT) == 1
                            && countType(beforeCrash, 3, LogType.COMPENSATION) == 1
                            && plan.abortedTx.contains(3L)
                            && !plan.uncommittedTx.contains(3L)
                            && !plan.needsAbortTx.contains(3L),
                    "AC-3 completed-abort state", "T3 has durable compensation and ABORT, unlike T2");

            MockStorageManager diskBeforeCrash = new MockStorageManager(dir);
            check(integerAt(diskBeforeCrash, committedRid) == null
                            && integerAt(diskBeforeCrash, activeRid) == 2
                            && integerAt(diskBeforeCrash, abortedRid) == 3,
                    "AC-4 pre-recovery page state",
                    "committed T1 is not on disk; active T2 and previously aborted T3 are on disk");

            close(frm);
            frm = null;

            MockStorageManager sm2 = new MockStorageManager(dir);
            restarted = open(sm2, new MockCCM(), dir.resolve("wal.log"));
            restarted.recover();

            check(integerAt(sm2, committedRid) == 1,
                    "AC-4 redo", "committed T1 is reconstructed from WAL");
            check(integerAt(sm2, activeRid) == null,
                    "AC-4 undo active", "uncommitted T2 is removed from flushed data");
            check(integerAt(sm2, abortedRid) == null,
                    "AC-4 retry completed abort", "T3's existing CLR is applied after restart");
            List<LogRecord> afterRecovery = restarted.readLogs(Optional.empty());
            check(countType(afterRecovery, 2, LogType.ABORT) == 1
                            && countType(afterRecovery, 3, LogType.ABORT) == 1
                            && countType(afterRecovery, 3, LogType.COMPENSATION) == 1,
                    "AC-3 terminal records", "recovery completes T2 without duplicating T3's CLR/ABORT");
        } finally {
            close(restarted);
            close(frm);
            cleanup(dir);
        }
    }

    private static void testAC5_OperationsAndRepeatedChanges() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        FailureRecoveryManager restarted = null;
        try {
            MockStorageManager sm = new MockStorageManager(dir);
            frm = open(sm, new MockCCM(), dir.resolve("wal.log"));
            frm.recover();

            RID updateRid = new RID(new PageId("account", 0), 0);
            RID deleteRid = new RID(new PageId("account", 0), 1);
            RID insertRid = new RID(new PageId("account", 0), 2);
            sm.seed("account", updateRid, List.of(RecordCodec.integer(1)));
            sm.seed("account", deleteRid, List.of(RecordCodec.integer(9)));
            sm.flushAll();

            frm.logBegin(1);
            logAndApply(frm, sm, 1, update(updateRid, 1, 2));
            logAndApply(frm, sm, 1, update(updateRid, 2, 3));
            logAndApply(frm, sm, 1, delete(deleteRid, 9));
            logAndApply(frm, sm, 1, insert(insertRid, 7));
            frm.logCommit(1);

            MockStorageManager diskBeforeCrash = new MockStorageManager(dir);
            check(integerAt(diskBeforeCrash, updateRid) == 1
                            && integerAt(diskBeforeCrash, deleteRid) == 9
                            && integerAt(diskBeforeCrash, insertRid) == null,
                    "AC-5 pre-recovery disk", "unflushed committed changes are absent from disk");

            close(frm);
            frm = null;

            MockStorageManager sm2 = new MockStorageManager(dir);
            restarted = open(sm2, new MockCCM(), dir.resolve("wal.log"));
            restarted.recover();
            check(integerAt(sm2, updateRid) == 3,
                    "AC-5 repeated UPDATE", "both updates on the same RID are redone in LSN order");
            check(integerAt(sm2, insertRid) == 7,
                    "AC-5 INSERT", "committed INSERT is redone");
            check(integerAt(sm2, deleteRid) == null,
                    "AC-5 DELETE", "committed DELETE is redone");
        } finally {
            close(restarted);
            close(frm);
            cleanup(dir);
        }
    }

    private static void testAC6_DataCorrectnessOnDisk() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        FailureRecoveryManager restarted = null;
        try {
            MockStorageManager sm = new MockStorageManager(dir);
            frm = open(sm, new MockCCM(), dir.resolve("wal.log"));
            frm.recover();

            RID noForceRid = new RID(new PageId("account", 0), 0);
            RID stealRid = new RID(new PageId("account", 0), 1);
            sm.seed("account", noForceRid, List.of(RecordCodec.integer(10)));
            sm.seed("account", stealRid, List.of(RecordCodec.integer(30)));
            sm.flushAll();

            frm.logBegin(1);
            frm.logChange(1, update(noForceRid, 10, 20));
            frm.logCommit(1);

            frm.logBegin(2);
            logAndApply(frm, sm, 2, update(stealRid, 30, 40));
            sm.flushAll();

            MockStorageManager diskBeforeCrash = new MockStorageManager(dir);
            check(integerAt(diskBeforeCrash, noForceRid) == 10
                            && integerAt(diskBeforeCrash, stealRid) == 40,
                    "AC-6 pre-recovery disk",
                    "committed T1 is unflushed while uncommitted T2 has reached disk");

            close(frm);
            frm = null;

            MockStorageManager sm2 = new MockStorageManager(dir);
            restarted = open(sm2, new MockCCM(), dir.resolve("wal.log"));
            restarted.recover();
            check(integerAt(sm2, noForceRid) == 20 && integerAt(sm2, stealRid) == 30,
                    "AC-6 recovered buffer",
                    "redo preserves the committed value and undo restores the old uncommitted value");

            MockStorageManager persisted = new MockStorageManager(dir);
            check(integerAt(persisted, noForceRid) == 20 && integerAt(persisted, stealRid) == 30,
                    "AC-6 recovered disk", "Storage Manager flushes the recovered result to disk");
        } finally {
            close(restarted);
            close(frm);
            cleanup(dir);
        }
    }

    private static void testAC7_LogDamageHandling() throws Exception {
        testAC7_TruncatedTailIsDiscarded();
        testAC7_MiddleCorruptionFailsStartup();
    }

    private static void testAC7_TruncatedTailIsDiscarded() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        FailureRecoveryManager restarted = null;
        try {
            Path walPath = dir.resolve("wal.log");
            MockStorageManager sm = new MockStorageManager(dir);
            frm = open(sm, new MockCCM(), walPath);
            frm.recover();

            RID rid = new RID(new PageId("account", 0), 0);
            frm.logBegin(1);
            long changeLsn = logAndApply(frm, sm, 1, insert(rid, 55));
            sm.flushAll();
            frm.logCommit(1);
            close(frm);
            frm = null;

            long completeSize = Files.size(walPath);
            Files.write(walPath, java.util.Arrays.copyOf(Files.readAllBytes(walPath),
                    Math.toIntExact(completeSize - 2)));
            long tornSize = Files.size(walPath);

            MockStorageManager sm2 = new MockStorageManager(dir);
            restarted = open(sm2, new MockCCM(), walPath);
            check(Files.size(walPath) < tornSize,
                    "AC-7 torn tail repair", "opening WAL truncates the incomplete final frame");
            RecoveryResult result = restarted.recover();
            List<LogRecord> records = restarted.readLogs(Optional.of(1L));
            check(result.lastRecoveredLSN() == changeLsn
                            && countType(records, 1, LogType.COMMIT) == 0,
                    "AC-7 incomplete record ignored",
                    "the torn COMMIT is not treated as complete and the transaction is undone");
            check(integerAt(sm2, rid) == null && countType(records, 1, LogType.ABORT) == 1,
                    "AC-7 recovery after torn tail",
                    "flushed data without a complete COMMIT is rolled back");
        } finally {
            close(restarted);
            close(frm);
            cleanup(dir);
        }
    }

    private static void testAC7_MiddleCorruptionFailsStartup() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        FailureRecoveryManager damaged = null;
        try {
            Path walPath = dir.resolve("wal.log");
            MockStorageManager sm = new MockStorageManager(dir);
            frm = open(sm, new MockCCM(), walPath);
            frm.recover();
            RID rid = new RID(new PageId("account", 0), 0);
            frm.logBegin(1);
            frm.logChange(1, insert(rid, 66));
            frm.logCommit(1);
            close(frm);
            frm = null;

            byte[] walBytes = Files.readAllBytes(walPath);
            int frameCount = countFrames(walBytes);
            if (frameCount < 4) {
                throw new AssertionError("Expected header and multiple complete WAL frames");
            }
            int corruptFrame = frameCount - 2;
            int payloadOffset = framePayloadOffset(walBytes, corruptFrame);
            walBytes[payloadOffset] ^= 0x01;
            Files.write(walPath, walBytes);

            Throwable startupFailure = null;
            try {
                damaged = open(new MockStorageManager(dir), new MockCCM(), walPath);
                damaged.recover();
            } catch (RuntimeException error) {
                startupFailure = error;
            }
            check(startupFailure != null,
                    "AC-7 middle corruption", "complete-frame corruption prevents startup recovery");
            check(startupFailure != null
                            && messageChainContains(startupFailure, "checksum mismatch"),
                    "AC-7 clear error", "the failure identifies the WAL checksum corruption");
        } finally {
            close(damaged);
            close(frm);
            cleanup(dir);
        }
    }

    private static int countFrames(byte[] bytes) {
        int count = 0;
        int offset = 0;
        while (offset < bytes.length) {
            if (bytes.length - offset < 12) {
                throw new AssertionError("Unexpected partial frame in test WAL");
            }
            int size = ByteBuffer.wrap(bytes, offset, Integer.BYTES).getInt();
            if (size < 0 || (long) offset + 12 + size > bytes.length) {
                throw new AssertionError("Invalid frame in test WAL");
            }
            offset += 12 + size;
            count++;
        }
        return count;
    }

    private static int framePayloadOffset(byte[] bytes, int frameIndex) {
        int offset = 0;
        for (int index = 0; index <= frameIndex; index++) {
            int size = ByteBuffer.wrap(bytes, offset, Integer.BYTES).getInt();
            if (index == frameIndex) {
                return offset + 12;
            }
            offset += 12 + size;
        }
        throw new AssertionError("Frame index out of range: " + frameIndex);
    }

    private static void testAC8_IdempotentRestart() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        FailureRecoveryManager firstRestart = null;
        FailureRecoveryManager secondRestart = null;
        try {
            MockStorageManager sm = new MockStorageManager(dir);
            frm = open(sm, new MockCCM(), dir.resolve("wal.log"));
            frm.recover();

            RID committedRid = new RID(new PageId("account", 0), 0);
            RID activeRid = new RID(new PageId("account", 0), 1);
            frm.logBegin(1);
            frm.logChange(1, insert(committedRid, 1));
            frm.logCommit(1);
            frm.logBegin(2);
            logAndApply(frm, sm, 2, insert(activeRid, 2));
            sm.flushAll();
            close(frm);
            frm = null;

            MockStorageManager sm1 = new MockStorageManager(dir);
            firstRestart = open(sm1, new MockCCM(), dir.resolve("wal.log"));
            firstRestart.recover();
            Map<String, Map<RID, RecordImage>> expected = Map.of(
                    "account", Map.of(committedRid, image(1)));
            Map<String, Map<RID, RecordImage>> firstDump = sm1.dumpAll();
            List<LogRecord> firstLogs = firstRestart.readLogs(Optional.empty());
            check(firstDump.equals(expected) && integerAt(sm1, activeRid) == null,
                    "AC-8 first restart expected data",
                    "committed row exists exactly once and uncommitted row is absent");
            check(countType(firstLogs, 2, LogType.COMPENSATION) == 1
                            && countType(firstLogs, 2, LogType.ABORT) == 1,
                    "AC-8 first restart recovery log", "undo has one durable CLR and one ABORT");
            close(firstRestart);
            firstRestart = null;

            MockStorageManager sm2 = new MockStorageManager(dir);
            secondRestart = open(sm2, new MockCCM(), dir.resolve("wal.log"));
            secondRestart.recover();
            Map<String, Map<RID, RecordImage>> secondDump = sm2.dumpAll();
            List<LogRecord> secondLogs = secondRestart.readLogs(Optional.empty());
            check(secondDump.equals(expected) && secondDump.equals(firstDump),
                    "AC-8 second restart expected data",
                    "the second restart matches the expected data and first restart exactly");
            check(sm2.dump("account").size() == 1
                            && countType(secondLogs, 2, LogType.COMPENSATION) == 1
                            && countType(secondLogs, 2, LogType.ABORT) == 1,
                    "AC-8 no duplicates", "recovery does not duplicate rows, CLR, or ABORT");
        } finally {
            close(secondRestart);
            close(firstRestart);
            close(frm);
            cleanup(dir);
        }
    }

    private static void testInterruptedUndoRetry() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager writer = null;
        FailureRecoveryManager interrupted = null;
        FailureRecoveryManager restarted = null;
        try {
            MockStorageManager sm = new MockStorageManager(dir);
            writer = open(sm, new MockCCM(), dir.resolve("wal.log"));
            writer.recover();

            RID firstRid = new RID(new PageId("account", 0), 0);
            RID secondRid = new RID(new PageId("account", 0), 1);
            writer.logBegin(1);
            logAndApply(writer, sm, 1, insert(firstRid, 1));
            logAndApply(writer, sm, 1, insert(secondRid, 2));
            sm.flushAll();
            close(writer);
            writer = null;

            FailingWriteStorageManager failingSm = new FailingWriteStorageManager(dir, 2);
            interrupted = open(failingSm, new MockCCM(), dir.resolve("wal.log"));
            boolean failedDuringUndo = false;
            try {
                interrupted.recover();
            } catch (FrmException error) {
                failedDuringUndo = error.getCode() == ErrorCode.RECOVERY_FAILURE
                        && error.getMessage().contains("UNDO_FAILED");
            }
            List<LogRecord> partialLogs = interrupted.readLogs(Optional.of(1L));
            check(failedDuringUndo && interrupted.getState() == RecoveryState.FAILED,
                    "mid-undo interruption", "a write failure stops recovery and marks the facade FAILED");
            check(countType(partialLogs, 1, LogType.COMPENSATION) == 2
                            && countType(partialLogs, 1, LogType.ABORT) == 0,
                    "mid-undo durable progress", "both CLR records are durable but ABORT is not yet logged");
            close(interrupted);
            interrupted = null;

            MockStorageManager sm2 = new MockStorageManager(dir);
            restarted = open(sm2, new MockCCM(), dir.resolve("wal.log"));
            restarted.recover();
            List<LogRecord> recoveredLogs = restarted.readLogs(Optional.of(1L));
            check(integerAt(sm2, firstRid) == null && integerAt(sm2, secondRid) == null,
                    "mid-undo retry result", "restart completes every undo despite the prior interruption");
            check(countType(recoveredLogs, 1, LogType.COMPENSATION) == 2
                            && countType(recoveredLogs, 1, LogType.ABORT) == 1,
                    "mid-undo retry log", "retry reuses existing CLRs and writes exactly one ABORT");
        } finally {
            close(restarted);
            close(interrupted);
            close(writer);
            cleanup(dir);
        }
    }

    private static void testAC9_FailStopOnError() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager writer = null;
        FailureRecoveryManager failedRecovery = null;
        FailureRecoveryManager restarted = null;
        try {
            Path walPath = dir.resolve("wal.log");
            MockStorageManager sm = new MockStorageManager(dir);
            writer = open(sm, new MockCCM(), walPath);
            writer.recover();
            RID rid = new RID(new PageId("account", 0), 0);
            writer.logBegin(1);
            logAndApply(writer, sm, 1, insert(rid, 1));
            sm.flushAll();
            close(writer);
            writer = null;

            FailingFlushStorageManager failingSm = new FailingFlushStorageManager(dir);
            failedRecovery = open(failingSm, new MockCCM(), walPath);
            FailureRecoveryManager manager = failedRecovery;
            FrmException failure = null;
            try {
                failedRecovery.recover();
            } catch (FrmException error) {
                failure = error;
            }
            check(failure != null && failure.getCode() == ErrorCode.RECOVERY_FAILURE
                            && failure.getMessage().contains("FLUSH_FAILED"),
                    "AC-9 injected recovery error",
                    "a final Storage Manager flush error is reported with its phase and recovery code");
            check(failedRecovery.getState() == RecoveryState.FAILED,
                    "AC-9 failed state", "FRM remains FAILED after recovery failure");
            check(rejectedAsNotReady(() -> manager.logBegin(2)),
                    "AC-9 BEGIN rejected", "new transactions cannot begin after recovery failure");
            check(rejectedAsNotReady(() -> manager.logCommit(1)),
                    "AC-9 COMMIT rejected", "COMMIT is rejected after recovery failure too");
            List<LogRecord> failedLogs = failedRecovery.readLogs(Optional.of(1L));
            check(countType(failedLogs, 1, LogType.COMPENSATION) == 1
                            && countType(failedLogs, 1, LogType.ABORT) == 1,
                    "AC-9 durable partial progress", "failure occurs after CLR and ABORT are durable");
            close(failedRecovery);
            failedRecovery = null;

            MockStorageManager sm2 = new MockStorageManager(dir);
            restarted = open(sm2, new MockCCM(), walPath);
            restarted.recover();
            List<LogRecord> recoveredLogs = restarted.readLogs(Optional.of(1L));
            check(integerAt(sm2, rid) == null
                            && countType(recoveredLogs, 1, LogType.COMPENSATION) == 1
                            && countType(recoveredLogs, 1, LogType.ABORT) == 1,
                    "AC-9 restart after failure",
                    "a fresh recovery completes the durable abort without duplicate log records");
        } finally {
            close(restarted);
            close(failedRecovery);
            close(writer);
            cleanup(dir);
        }
    }

    private static void testWalFailureCode() throws Exception {
        Path dir = createTempDir();
        FailureRecoveryManager frm = null;
        LogManager closedLog = null;
        try {
            Path walPath = dir.resolve("wal.log");
            closedLog = new LogManager(walPath.toString());
            closedLog.close();

            MockStorageManager sm = new MockStorageManager(dir);
            FrmConfig config = FrmConfig.defaults(walPath)
                    .withCheckpointInterval(Duration.ZERO);
            frm = new FailureRecoveryManager(sm, new MockCCM(), config, closedLog);
            sm.setWal(frm::ensureDurable);

            FrmException failure = null;
            try {
                frm.recover();
            } catch (FrmException error) {
                failure = error;
            }
            check(failure != null && failure.getCode() == ErrorCode.WAL_FAILURE,
                    "WAL failure code", "a WAL access failure during recovery remains WAL_FAILURE");
            check(failure != null && failure.getMessage().contains("LOG_READ_FAILED")
                            && frm.getState() == RecoveryState.FAILED,
                    "WAL failure context", "the public error includes the failed recovery phase");
        } finally {
            close(frm);
            if (closedLog != null) {
                closedLog.close();
            }
            cleanup(dir);
        }
    }

    private static final class FailingFlushStorageManager extends MockStorageManager {
        private FailingFlushStorageManager(Path dataDir) {
            super(dataDir);
        }

        @Override
        public synchronized void flushAll() {
            throw new IllegalStateException("injected flush failure for AC-9");
        }
    }

    private static final class FailingWriteStorageManager extends MockStorageManager {
        private final int failOnWrite;
        private int writes;

        private FailingWriteStorageManager(Path dataDir, int failOnWrite) {
            super(dataDir);
            this.failOnWrite = failOnWrite;
        }

        @Override
        public synchronized void writeAt(
                String table, RID rid, RecordImage image, long lsn) {
            writes++;
            if (writes == failOnWrite) {
                throw new IllegalStateException("injected recovery write failure");
            }
            super.writeAt(table, rid, image, lsn);
        }
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
