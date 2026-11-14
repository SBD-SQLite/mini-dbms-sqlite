package sqlike.frm.log;

import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import sqlike.shared.*;
import static sqlike.frm.log.WalTestSupport.*;

final class LogManagerTests {
    static void runAll() {
        run("Log kosong", LogManagerTests::empty);
        run("INSERT, UPDATE, DELETE, COMMIT dan restart", LogManagerTests::changes);
        run("Input dan urutan transaksi yang salah ditolak", LogManagerTests::invalidInput);
        run("Compensation dan ABORT", LogManagerTests::abort);
        run("Filter dan tampilan log", LogManagerTests::readLogs);
        run("Batas LSN dan kegagalan penulisan", LogManagerTests::writeFailure);
        run("Transaksi bersamaan tidak membuat LSN ganda", LogManagerTests::concurrent);
        run("Pergantian log menyimpan history", LogManagerTests::rotation);
    }

    private static void empty() throws Exception {
        try (TestLog test = new TestLog()) {
            eq(0L, test.wal.getLastLsn());
            eq(List.of(), test.wal.readLogs(null));
            eq("(log kosong)", test.wal.showLogs(null));
            test.wal.ensureDurable(0);
        }
    }

    private static void changes() throws Exception {
        try (TestLog test = new TestLog()) {
            DataChange insert = change(ChangeOperation.INSERT, ABSENT, row(10));
            DataChange update = change(ChangeOperation.UPDATE, row(10), row(20));
            DataChange delete = change(ChangeOperation.DELETE, row(20), ABSENT);
            eq(1L, test.wal.logBegin(1));
            eq(2L, test.wal.logChange(1, insert));
            eq(3L, test.wal.logChange(1, update));
            eq(4L, test.wal.logChange(1, delete));
            test.wal.logCommit(1);

            List<LogRecord> logs = test.wal.readLogs(null);
            eq(List.of(LogType.BEGIN, LogType.INSERT, LogType.UPDATE, LogType.DELETE, LogType.COMMIT), logs.stream().map(LogRecord::type).toList());
            eq(insert, logs.get(1).change().orElseThrow());
            eq(update, logs.get(2).change().orElseThrow());
            eq(delete, logs.get(3).change().orElseThrow());
            test.reopen();
            eq(logs, test.wal.readLogs(null));
            eq(6L, test.wal.logBegin(2));
        }
    }

    private static void invalidInput() throws Exception {
        try (TestLog test = new TestLog()) {
            expect(WalException.class, () -> test.wal.logBegin(0));
            expect(WalException.class, () -> test.wal.logCommit(1));
            test.wal.logBegin(1);
            expect(WalException.class, () -> test.wal.logBegin(1));
            expect(WalException.class, () -> test.wal.logChange(1, null));
            DataChange data = change(ChangeOperation.INSERT, row(10), row(20));
            expect(WalException.class, () -> test.wal.logChange(1, data));
            eq(1L, test.wal.getLastLsn());
            test.wal.logCommit(1);
            expect(WalException.class, () -> test.wal.logCommit(1));
        }
    }

    private static void abort() throws Exception {
        try (TestLog test = new TestLog()) {
            test.wal.logBegin(1);
            long a = test.wal.logChange(1, change(ChangeOperation.INSERT, ABSENT, row(10)));
            long b = test.wal.logChange(1, change(ChangeOperation.UPDATE, row(10), row(20)));
            expect(WalException.class, () -> test.wal.logAbort(1));
            expect(WalException.class, () -> test.wal.logCompensation(1, a));

            LogRecord clr = test.wal.logCompensation(1, b);
            eq(row(10), clr.change().orElseThrow().after());
            eq(Optional.of(b), clr.undoOfLSN());
            eq(clr, test.wal.logCompensation(1, b));
            expect(WalException.class, () -> test.wal.logAbort(1));
            expect(WalException.class, () -> test.wal.logCommit(1));
            eq(ABSENT, test.wal.logCompensation(1, a).change().orElseThrow().after());
            test.wal.logAbort(1);
            List<LogRecord> logs = test.wal.readLogs(1L);
            eq(LogType.ABORT, logs.get(logs.size() - 1).type());
            test.wal.logAbort(1);
            eq(logs, test.wal.readLogs(1L));
        }
    }

    private static void readLogs() throws Exception {
        try (TestLog test = new TestLog()) {
            test.wal.logBegin(1);
            test.wal.logBegin(2);
            test.wal.logChange(1, change(ChangeOperation.INSERT, ABSENT, row(10)));
            test.wal.logCommit(2);
            test.wal.logCommit(1);
            var cp = test.wal.logCheckpoint(5, "snapshot-test");
            byte[] data = Files.readAllBytes(test.path);

            eq(List.of(1L, 3L, 5L), test.wal.readLogs(1L).stream().map(LogRecord::lsn).toList());
            eq(List.of(5L, 3L, 1L), test.wal.readLogsBackward(1).stream().map(LogRecord::lsn).toList());
            String text = test.wal.showLogs(1L);
            check(text.contains("T1") && text.contains("INSERT") && text.contains("account"), "Isi tampilan log tidak lengkap");
            check(!text.contains("T2"), "Filter transaksi salah");
            check(text.indexOf("LSN=1 ") < text.indexOf("LSN=3 ") && text.indexOf("LSN=3 ") < text.indexOf("LSN=5 "), "Urutan tampilan salah");
            eq("(log kosong)", test.wal.showLogs(99L));
            text = test.wal.showLogs(null);
            check(text.contains("<checkpoint {}> " + cp), "Metadata checkpoint tidak tampil");
            check(text.contains("flushedThroughLSN=5") && text.contains("snapshotId=snapshot-test"), "Informasi checkpoint tidak lengkap");
            check(Arrays.equals(data, Files.readAllBytes(test.path)), "Pembacaan mengubah file log");
        }
    }

    private static void writeFailure() throws Exception {
        try (TestLog test = new TestLog()) {
            test.wal.logBegin(1);
            test.wal.ensureDurable(1);
            expect(WalException.class, () -> test.wal.ensureDurable(2));
            expect(WalException.class, () -> test.wal.ensureDurable(-1));

            // Tutup channel untuk mensimulasikan kegagalan I/O.
            Field field = LogManager.class.getDeclaredField("channel");
            field.setAccessible(true);
            ((FileChannel) field.get(test.wal)).close();
            WalException error = expect(WalException.class, () -> test.wal.logCommit(1));
            eq(ErrorCode.WAL_FAILURE, error.getErrorCode());
            check(error.isRecoveryRequired() && error.isCommitUnknown(), "Flag kegagalan COMMIT salah");
            expect(WalException.class, () -> test.wal.logBegin(2));
        }
    }

    private static void concurrent() throws Exception {
        try (TestLog test = new TestLog()) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> jobs = new ArrayList<>();
                for (long i = 1; i <= 4; i++) {
                    long tx = i;
                    jobs.add(pool.submit(() -> {
                        test.wal.logBegin(tx);
                        test.wal.logChange(tx, change(ChangeOperation.INSERT, ABSENT, row((int) tx)));
                        test.wal.logCommit(tx);
                    }));
                }
                for (Future<?> job : jobs) {
                    job.get(10, TimeUnit.SECONDS);
                }
                List<LogRecord> logs = test.wal.readLogs(null);
                eq(12, logs.size());
                for (int i = 0; i < logs.size(); i++) {
                    eq((long) i + 1, logs.get(i).lsn());
                }
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void rotation() throws Exception {
        try (TestLog test = new TestLog()) {
            test.wal.logBegin(1);
            expect(WalException.class, () -> test.wal.logCheckpoint(1, "snapshot"));
            test.wal.logCommit(1);
            test.wal.logCheckpoint(2, "snapshot");
            test.wal.archiveActiveLog();
            test.wal.startNewLog();
            eq(1, test.wal.readLogs(null).size());
            eq(4, test.wal.readHistory().size());
            List<LogRecord> history = test.wal.readHistory();
            test.reopen();
            eq(history, test.wal.readHistory());
            eq(5L, test.wal.logBegin(2));
        }
    }
}
