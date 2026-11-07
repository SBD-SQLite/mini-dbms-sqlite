package sqlike.frm.log;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import sqlike.shared.*;
import sqlike.frm.checkpoint.*;
import static java.nio.file.StandardOpenOption.*;

// Satu penulis untuk setiap WAL. Append baru dianggap berhasil setelah force().
public final class LogManager implements IWalDurability, CheckpointLogPort, AutoCloseable {
    private static final String WAL_FORMAT = "SQLIKE-WAL-2";
    private static final String MANIFEST_FORMAT = "SQLIKE-MANIFEST-1";

    private final Path path;
    private final Path manifest;
    private FileChannel lockChannel;
    private FileChannel channel;
    private FileLock fileLock;

    private final List<Path> logFiles = new ArrayList<>();
    private final List<WalEpoch> epochs = new ArrayList<>();
    private final List<LogRecord> activeLogs = new ArrayList<>();
    private final List<LogRecord> history = new ArrayList<>();
    private long lastLsn;
    private long maxTxId;
    private long archivedThrough = -1;
    private boolean closed;
    private boolean failed;

    public LogManager(String logPath) {
        path = Path.of(logPath).toAbsolutePath().normalize();
        manifest = path.resolveSibling(path.getFileName() + ".manifest");
        try {
            Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
            lockChannel = FileChannel.open(lockPath, CREATE, WRITE);
            fileLock = lockChannel.tryLock();
            if (fileLock == null) {
                throw new IOException("WAL is already open");
            }

            if (Files.exists(manifest)) {
                loadManifest();
            } else {
                createFirstLog();
            }
            loadLogs();
            Path activePath = logFiles.get(logFiles.size() - 1);
            channel = FileChannel.open(activePath, READ, WRITE);
            channel.position(channel.size());
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            try {
                close();
            } catch (RuntimeException closeError) {
                e.addSuppressed(closeError);
            }
            throw new WalException(ErrorCode.WAL_FAILURE, "Cannot open WAL: " + e.getMessage(), e, true, false);
        }
    }

    public synchronized long logBegin(long txId) {
        checkReady();
        if (txId <= 0) {
            throw invalidState("Transaction ID must be positive");
        }
        for (LogRecord record : history) {
            if (belongsTo(record, txId)) {
                throw invalidState("Transaction ID has already been used: " + txId);
            }
        }
        return append(newRecord(txId, LogType.BEGIN, null, null, null)).lsn();
    }

    public synchronized long logChange(long txId, DataChange change) {
        checkActive(txId);
        checkNotUndoing(txId);
        if (change == null || change.operation() == null) {
            throw invalidValue("DataChange and operation are required", null);
        }
        LogType type = LogType.valueOf(change.operation().name());
        return append(newRecord(txId, type, change, null, null)).lsn();
    }

    public synchronized long logSchemaChange(long txId, SchemaChange change) {
        checkActive(txId);
        checkNotUndoing(txId);
        if (change == null) {
            throw invalidValue("SchemaChange is required", null);
        }
        return append(newRecord(txId, LogType.SCHEMA_CHANGE, null, change, null)).lsn();
    }

    public synchronized void logCommit(long txId) {
        checkActive(txId);
        checkNotUndoing(txId);
        append(newRecord(txId, LogType.COMMIT, null, null, null));
    }

    // Harus nyelesaikan undo sebelum catet ABORT
    public synchronized void logAbort(long txId) {
        checkReady();
        for (LogRecord record : history) {
            if (belongsTo(record, txId) && record.type() == LogType.ABORT) {
                return;
            }
        }
        checkActive(txId);

        Set<Long> undone = new HashSet<>();
        for (LogRecord record : activeLogs) {
            if (belongsTo(record, txId) && record.undoOfLSN().isPresent()) {
                undone.add(record.undoOfLSN().get());
            }
        }
        for (LogRecord record : activeLogs) {
            LogType type = record.type();
            boolean isChange = type == LogType.INSERT || type == LogType.UPDATE
                    || type == LogType.DELETE || type == LogType.SCHEMA_CHANGE;
            if (belongsTo(record, txId) && isChange && !undone.contains(record.lsn())) {
                throw invalidState("Rollback belum selesai untuk T" + txId);
            }
        }

        append(newRecord(txId, LogType.ABORT, null, null, null));
    }

    public synchronized LogRecord logCompensation(long txId, long originalLsn) {
        checkActive(txId);
        List<LogRecord> records = readLogsBackward(txId);
        Set<Long> undone = new HashSet<>();
        for (LogRecord record : records) {
            if (record.undoOfLSN().isPresent()) {
                long undoLsn = record.undoOfLSN().get();
                undone.add(undoLsn);
                if (undoLsn == originalLsn) {
                    if (record.lsn() != records.get(0).lsn()) {
                        throw invalidState("Only the latest compensation record can be retried");
                    }
                    return record;
                }
            }
        }

        LogRecord original = findChangeToUndo(records, undone);
        if (original.lsn() != originalLsn) {
            throw invalidState("Undo must follow descending original LSN");
        }

        if (original.change().isPresent()) {
            DataChange change = original.change().get();
            ChangeOperation operation = switch (change.operation()) {
                case INSERT -> ChangeOperation.DELETE;
                case DELETE -> ChangeOperation.INSERT;
                case UPDATE -> ChangeOperation.UPDATE;
            };
            DataChange inverse = new DataChange(operation, change.tableName(), change.rid(), change.after(), change.before());
            return append(newRecord(txId, LogType.COMPENSATION, inverse, null, originalLsn));
        }

        SchemaChange change = original.schemaChange().orElseThrow();
        SchemaChange inverse = new SchemaChange(change.after(), change.before());
        return append(newRecord(txId, LogType.COMPENSATION, null, inverse, originalLsn));
    }

    private static LogRecord findChangeToUndo(List<LogRecord> records, Set<Long> undone) {
        for (LogRecord record : records) {
            LogType type = record.type();
            boolean isChange = type == LogType.INSERT || type == LogType.UPDATE || type == LogType.DELETE || type == LogType.SCHEMA_CHANGE;
            if (isChange && !undone.contains(record.lsn())) {
                return record;
            }
        }
        throw invalidState("No change to undo");
    }

    @Override
    public synchronized CheckpointInfo logCheckpoint(long flushedThroughLSN, String snapshotId) {
        checkNoActiveTransactions();
        if (flushedThroughLSN != lastLsn) {
            throw invalidState("Checkpoint boundary must equal the current last LSN");
        }
        CheckpointInfo checkpoint = newCheckpoint(snapshotId);
        append(checkpointRecord(checkpoint));
        return checkpoint;
    }

    @Override
    public synchronized void ensureDurable(long lsn) {
        checkReady();
        if (lsn < 0 || lsn > lastLsn) {
            throw invalidState("Requested LSN is outside WAL");
        }
    }

    @Override
    public synchronized long getLastLsn() {
        checkReady();
        return lastLsn;
    }

    public synchronized long getMaxTxId() {
        checkReady();
        return maxTxId;
    }

    public synchronized List<WalEpoch> readEpochs() {
        checkReady();
        return List.copyOf(epochs);
    }

    // log aktif doang, pake readHistory() kalau mau ada epoch sebelumnya.
    public synchronized List<LogRecord> readLogs(Long txId) {
        checkReady();
        List<LogRecord> result = new ArrayList<>();
        for (LogRecord record : activeLogs) {
            if (txId == null || belongsTo(record, txId)) {
                result.add(record);
            }
        }
        return List.copyOf(result);
    }

    public synchronized List<LogRecord> readLogsBackward(long txId) {
        List<LogRecord> result = new ArrayList<>(readLogs(txId));
        Collections.reverse(result);
        return List.copyOf(result);
    }

    public synchronized List<LogRecord> readHistory() {
        checkReady();
        return List.copyOf(history);
    }

    public synchronized String showLogs(Long txId) {
        List<LogRecord> records = readLogs(txId);
        if (records.isEmpty()) {
            return "(log kosong)";
        }
        StringBuilder result = new StringBuilder();
        for (LogRecord record : records) {
            result.append("LSN=").append(record.lsn()).append(" time=").append(record.time()).append(" type=").append(record.type())
                .append(" ").append(formatRecord(record)).append(System.lineSeparator());
        }
        return result.toString();
    }
    private String activeTransactionsBefore(long lsn) {
        Set<Long> active = new TreeSet<>();
        for (LogRecord r : history) {
            if (r.lsn() >= lsn) {
                break;
            }
            if (r.txID().isEmpty()) {
                continue;
            }
            long id = r.txID().get();
            switch (r.type()) {
                case BEGIN -> active.add(id);
                case COMMIT, ABORT -> active.remove(id);
                default -> { }
            }
        }
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (long id : active) {
            joiner.add("T" + id);
        }
        return joiner.toString();
    }

    private String formatRecord(LogRecord record) {
        String tx = "T" + record.txID().orElse(0L);
        return switch (record.type()) {
            case BEGIN -> "<" + tx + " start>";
            case COMMIT -> "<" + tx + " commit>";
            case ABORT -> "<" + tx + " abort>";
            case CHECKPOINT -> "<checkpoint " + activeTransactionsBefore(record.lsn()) + ">";
            case SCHEMA_CHANGE -> "<" + tx + ", schema, " + record.schemaChange().orElseThrow() + ">";
            case COMPENSATION -> {
                if (record.change().isPresent()) {
                    DataChange c = record.change().get();
                    yield "<" + tx + ", " + c.tableName() + "[" + c.rid() + "], " + formatImage(c.after()) + ">";
                }
                SchemaChange s = record.schemaChange().orElseThrow();
                yield "<" + tx + ", schema, " + s.after() + ">";
            }
            default -> {
                DataChange change = record.change().orElseThrow();
                yield "<" + tx + ", " + change.tableName() + "[" + change.rid() + "], " + formatImage(change.before()) + ", " + formatImage(change.after()) + ">";
            }
        };
    }

    private static String formatImage(RecordImage image) {
        if (!image.exists()) {
            return "<record absent>";
        }
        List<String> values = new ArrayList<>();
        for (Value value : image.values()) {
            String text = switch (value.type()) {
                case INTEGER -> value.integerValue().orElseThrow().toString();
                case FLOAT -> value.floatValue().orElseThrow().toString();
                case CHAR, VARCHAR -> "\"" + value.textValue().orElseThrow().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
            };
            values.add(text);
        }
        return values.toString();
    }

    public synchronized String archiveActiveLog() {
        checkNoActiveTransactions();
        Path source = logFiles.get(logFiles.size() - 1);
        Path archive = path.resolveSibling(path.getFileName() + ".archive-" + UUID.randomUUID());
        try {
            Files.copy(source, archive);
            try (FileChannel file = FileChannel.open(archive, WRITE)) {
                file.force(true);
            }
            WalFileIO.forceDirectory(path.getParent());
            archivedThrough = lastLsn;
            return archive.toString();
        } catch (IOException e) {
            throw diskFailure("Cannot archive WAL", e, false);
        }
    }

    public synchronized void startNewLog() {
        checkNoActiveTransactions();
        if (archivedThrough != lastLsn || activeLogs.isEmpty()
                || activeLogs.get(activeLogs.size() - 1).type() != LogType.CHECKPOINT) {
            throw invalidState("Archive a completed checkpoint first, or use startNewLog(snapshotId, targetLSN)");
        }
        CheckpointInfo checkpoint = activeLogs.get(activeLogs.size() - 1).checkpoint().orElseThrow();
        startNewLog(checkpoint.snapshotId(), lastLsn);
    }

    public synchronized CheckpointInfo startNewLog(String snapshotId, long targetLSN) {
        checkNoActiveTransactions();
        if (targetLSN < 0 || targetLSN > lastLsn) {
            throw invalidState("Invalid new timeline boundary");
        }
        CheckpointInfo checkpoint = newCheckpoint(snapshotId);
        LogRecord marker = checkpointRecord(checkpoint);
        Path next = path.resolveSibling(path.getFileName() + ".epoch-" + UUID.randomUUID());
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            buffer.write(encodeHeader(lastLsn, maxTxId, snapshotId, targetLSN));
            buffer.write(WalFileIO.frame(LogRecordEncoder.encode(marker)));
            WalFileIO.create(next, buffer.toByteArray());

            List<Path> updated = new ArrayList<>(logFiles);
            updated.add(next);
            saveManifest(updated);
            channel.close();
            channel = FileChannel.open(next, READ, WRITE);
            channel.position(channel.size());

            int lastIndex = epochs.size() - 1;
            WalEpoch previous = epochs.get(lastIndex);
            epochs.set(lastIndex, new WalEpoch(previous.baseLSN(), previous.targetLSN(), previous.maxTxId(), previous.snapshotId(), false));
            epochs.add(new WalEpoch(lastLsn, targetLSN, maxTxId, Optional.of(snapshotId), true));
            logFiles.add(next);
            activeLogs.clear();
            addToMemory(marker, true);
            archivedThrough = -1;
            return checkpoint;
        } catch (IOException e) {
            throw diskFailure("New WAL activation is uncertain; reopen through recovery", e, false);
        }
    }

    private LogRecord newRecord(long txId, LogType type, DataChange change, SchemaChange schema, Long undoLsn) {
        try {
            return new LogRecord(nextLsn(), Instant.now(), Optional.of(txId), type, Optional.ofNullable(change), Optional.empty(), Optional.ofNullable(schema), Optional.ofNullable(undoLsn));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalidValue("Invalid log payload: " + e.getMessage(), e);
        }
    }

    private CheckpointInfo newCheckpoint(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw invalidValue("Snapshot ID is required", null);
        }
        return new CheckpointInfo(nextLsn(), lastLsn, Instant.now(), snapshotId);
    }

    private static LogRecord checkpointRecord(CheckpointInfo checkpoint) {
        return new LogRecord(checkpoint.checkpointLSN(), checkpoint.createdAt(), Optional.empty(), LogType.CHECKPOINT, Optional.empty(), Optional.of(checkpoint));
    }

    private long nextLsn() {
        if (lastLsn == Long.MAX_VALUE) {
            throw invalidState("LSN exhausted");
        }
        return lastLsn + 1;
    }

    private LogRecord append(LogRecord record) {
        byte[] data;
        try {
            data = WalFileIO.frame(LogRecordEncoder.encode(record));
        } catch (IOException | IllegalArgumentException | NullPointerException | NoSuchElementException e) {
            throw invalidValue("Cannot encode log record", e);
        }
        try {
            WalFileIO.write(channel, data);
            channel.force(true);
            addToMemory(record, true);
            return record;
        } catch (IOException e) {
            throw diskFailure("WAL append or force failed", e, record.type() == LogType.COMMIT);
        }
    }

    // Dipanggil pas record tersimpan, atau pas baca record dari disk.
    private void addToMemory(LogRecord record, boolean isActive) {
        history.add(record);
        if (isActive) {
            activeLogs.add(record);
        }
        lastLsn = record.lsn();
        maxTxId = Math.max(maxTxId, record.txID().orElse(0L));
    }

    private static boolean belongsTo(LogRecord record, long txId) {
        return record.txID().isPresent() && record.txID().get() == txId;
    }

    private void checkActive(long txId) {
        checkReady();
        if (txId <= 0) {
            throw invalidState("Transaction is not active: " + txId);
        }
        for (int i = activeLogs.size() - 1; i >= 0; i--) {
            LogRecord record = activeLogs.get(i);
            if (!belongsTo(record, txId)) {
                continue;
            }
            if (record.type() == LogType.COMMIT || record.type() == LogType.ABORT) {
                break;
            }
            return;
        }
        throw invalidState("Transaction is not active: " + txId);
    }

    private void checkNotUndoing(long txId) {
        for (LogRecord record : activeLogs) {
            if (belongsTo(record, txId) && record.type() == LogType.COMPENSATION) {
                throw invalidState("Transaction is rolling back");
            }
        }
    }

    private void checkNoActiveTransactions() {
        checkReady();
        Set<Long> pending = new HashSet<>();
        for (LogRecord record : activeLogs) {
            if (record.type() == LogType.BEGIN) {
                pending.add(record.txID().orElseThrow());
            } else if (record.type() == LogType.COMMIT || record.type() == LogType.ABORT) {
                pending.remove(record.txID().orElseThrow());
            }
        }
        if (!pending.isEmpty()) {
            throw invalidState("Maintenance requires all transactions to finish");
        }
    }

    private void checkReady() {
        if (closed || failed) {
            throw new WalException(ErrorCode.INVALID_STATE, "WAL is closed or requires recovery", null, failed, false);
        }
    }

    private static WalException invalidState(String message) {
        return new WalException(ErrorCode.INVALID_STATE, message, null, false, false);
    }

    private static WalException invalidValue(String message, Throwable cause) {
        return new WalException(ErrorCode.INVALID_VALUE, message, cause, false, false);
    }

    private WalException diskFailure(String message, Throwable cause, boolean commitUnknown) {
        failed = true;
        return new WalException(ErrorCode.WAL_FAILURE, message, cause, true, commitUnknown);
    }

    private void createFirstLog() throws IOException {
        if (Files.exists(path) && Files.size(path) != 0) {
            throw new IOException("Existing WAL has no manifest; explicit migration or recovery is required");
        }
        byte[] header = encodeHeader(0, 0, "", 0);
        if (Files.exists(path)) {
            try (FileChannel file = FileChannel.open(path, WRITE)) {
                WalFileIO.write(file, header);
                file.force(true);
            }
        } else {
            WalFileIO.create(path, header);
        }
        logFiles.add(path);
        saveManifest(logFiles);
    }

    private void loadLogs() throws IOException {
        for (int i = 0; i < logFiles.size(); i++) {
            boolean isActive = i == logFiles.size() - 1;
            List<byte[]> frames = WalFileIO.read(logFiles.get(i), isActive);
            if (frames.isEmpty()) {
                throw new IOException("Missing WAL header");
            }
            WalEpoch epoch = readHeader(frames.get(0), isActive);
            if (epoch.baseLSN() != lastLsn || epoch.maxTxId() < maxTxId || epoch.targetLSN() < 0 || epoch.targetLSN() > epoch.baseLSN()) {
                throw new IOException("Invalid epoch boundary");
            }
            if (i > 0) {
                checkBaseline(frames, epoch);
            }
            epochs.add(epoch);
            maxTxId = epoch.maxTxId();
            for (int j = 1; j < frames.size(); j++) {
                LogRecord record = LogRecordEncoder.decode(frames.get(j));
                if (lastLsn == Long.MAX_VALUE || record.lsn() != lastLsn + 1) {
                    throw new IOException("WAL LSN discontinuity");
                }
                addToMemory(record, isActive);
            }
        }
    }

    private static void checkBaseline(List<byte[]> frames, WalEpoch epoch) throws IOException {
        if (frames.size() < 2 || epoch.snapshotId().isEmpty()) {
            throw new IOException("Epoch has no durable baseline checkpoint");
        }
        LogRecord first = LogRecordEncoder.decode(frames.get(1));
        if (first.type() != LogType.CHECKPOINT) {
            throw new IOException("Epoch must start with a checkpoint");
        }
        CheckpointInfo checkpoint = first.checkpoint().orElseThrow();
        if (checkpoint.flushedThroughLSN() != epoch.baseLSN() || !checkpoint.snapshotId().equals(epoch.snapshotId().get())) {
            throw new IOException("Epoch baseline does not match its checkpoint");
        }
    }

    private static byte[] encodeHeader(long baseLsn, long maxTxId, String snapshot, long targetLsn) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        LogRecordEncoder.writeText(out, WAL_FORMAT);
        out.writeLong(baseLsn);
        out.writeLong(maxTxId);
        LogRecordEncoder.writeText(out, snapshot);
        out.writeLong(targetLsn);
        return WalFileIO.frame(buffer.toByteArray());
    }

    private static WalEpoch readHeader(byte[] data, boolean isActive) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (!LogRecordEncoder.readText(in).equals(WAL_FORMAT)) {
            throw new IOException("Unknown WAL format");
        }
        long baseLsn = in.readLong();
        long maxTxId = in.readLong();
        String snapshot = LogRecordEncoder.readText(in);
        long targetLsn = in.readLong();
        if (in.available() != 0) {
            throw new IOException("Trailing WAL header bytes");
        }
        Optional<String> snapshotId = snapshot.isEmpty() ? Optional.empty() : Optional.of(snapshot);
        return new WalEpoch(baseLsn, targetLsn, maxTxId, snapshotId, isActive);
    }

    private void saveManifest(List<Path> files) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        LogRecordEncoder.writeText(out, MANIFEST_FORMAT);
        out.writeInt(files.size());
        for (Path file : files) {
            LogRecordEncoder.writeText(out, file.getFileName().toString());
        }
        WalFileIO.replaceFileAtomically(manifest, WalFileIO.frame(buffer.toByteArray()));
    }

    private void loadManifest() throws IOException {
        List<byte[]> frames = WalFileIO.read(manifest, false);
        if (frames.size() != 1) {
            throw new IOException("Invalid manifest");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frames.get(0)));
        if (!LogRecordEncoder.readText(in).equals(MANIFEST_FORMAT)) {
            throw new IOException("Unsupported manifest");
        }
        int count = in.readInt();
        if (count <= 0 || count > in.available()) {
            throw new IOException("Invalid epoch count");
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String name = LogRecordEncoder.readText(in);
            Path fileName = Path.of(name);
            if (name.isBlank() || name.equals(".") || name.equals("..") || fileName.isAbsolute() || !fileName.getFileName().toString().equals(name) || !names.add(name)) {
                throw new IOException("Invalid epoch path");
            }
            logFiles.add(path.resolveSibling(name));
        }
        if (in.available() != 0) {
            throw new IOException("Trailing manifest bytes");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        AutoCloseable[] resources = {channel, fileLock, lockChannel};
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new IOException("Cannot close WAL resource", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw new WalException(ErrorCode.WAL_FAILURE, "Cannot close WAL", failure, true, false);
        }
    }
}
