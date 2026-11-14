package sqlike.frm.rollback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import sqlike.frm.FrmException;
import sqlike.frm.log.LogManager;
import sqlike.frm.log.LogRecord;
import sqlike.frm.log.LogType;
import sqlike.frm.port.RollbackPort;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;
import sqlike.shared.ErrorCode;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;

/**
 * Undoes one transaction during normal operation (FR3, FR-04, PRD v1.0 §11).
 *
 * <p>Reads the transaction's log records newest first. For every change (DML or DDL) that is not
 * undone yet, it first writes a COMPENSATION record through FR2 ({@code logCompensation(txId,
 * originalLsn)}, forced to disk), then writes the before-image back through SM on the same RID (or
 * restores the previous schema image) with the LSN of that compensation record. UPDATE returns to
 * the old values, INSERT disappears, DELETE comes back, and SCHEMA_CHANGE reverts catalog/indexes.
 * FR2 accepts {@code logAbort} only after every change has a compensation record; CCM writes that
 * final ABORT after this method returns.
 *
 * <p>Repeating a rollback after a failure part-way is safe: changes that already have a
 * compensation record are skipped, except the newest one, whose SM write may not have finished
 * and is written again (same before-image, same compensation LSN). Once the ABORT is logged, a
 * repeated rollback changes nothing, since another transaction may already have changed those
 * records.
 */
public class RollbackManager implements RollbackPort {

    private final LogManager logManager;
    private final SmPort storageManager;

    public RollbackManager(LogManager logManager, SmPort storageManager) {
        this.logManager = Objects.requireNonNull(logManager, "logManager");
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
    }

    @Override
    public void rollback(long txId) {
        if (txId <= 0) {
            throw new FrmException(ErrorCode.INVALID_VALUE, "txId must be positive: " + txId);
        }
        List<LogRecord> records = logManager.readLogs(txId);
        if (records.isEmpty()) {
            throw new FrmException(ErrorCode.NOT_FOUND, "rollback: unknown transaction " + txId);
        }
        if (has(records, LogType.COMMIT)) {
            throw new FrmException(ErrorCode.INVALID_STATE,
                    "rollback: transaction " + txId + " is committed and cannot be undone");
        }
        if (has(records, LogType.ABORT)) {
            return; // abort already finished
        }
        undoTxChanges(txId);
    }

    /**
     * Compensates and writes back the before-image of every change (data and schema) of {@code txId}
     * that is not undone yet, newest first. SM failures are not caught: the caller keeps the
     * transaction FAILED and may retry.
     *
     * @return number of undo operations applied
     */
    public int undoTxChanges(long txId) {
        List<LogRecord> records = new ArrayList<>();
        for (LogRecord record : logManager.readLogsBackward(txId)) {
            if (record.txID().equals(Optional.of(txId))) {
                records.add(record); // defensive: only this transaction's records
            }
        }

        Set<Long> compensated = new HashSet<>();
        Long newestCompensated = null; // its SM write may not have finished before a failure
        for (LogRecord record : records) {
            if (record.type() == LogType.COMPENSATION && record.undoOfLSN().isPresent()) {
                long original = record.undoOfLSN().get();
                compensated.add(original);
                if (newestCompensated == null) {
                    newestCompensated = original;
                }
            }
        }

        int written = 0;
        for (LogRecord record : records) {
            boolean hasDataUndo = isDataChange(record.type()) && record.change().isPresent();
            boolean hasSchemaUndo = record.type() == LogType.SCHEMA_CHANGE && record.schemaChange().isPresent();

            if (!hasDataUndo && !hasSchemaUndo) {
                continue; 
            }
            if (compensated.contains(record.lsn()) && !Objects.equals(newestCompensated, record.lsn())) {
                continue; // Sudah di-undo pada percobaan rollback sebelumnya
            }

            // 1. Catat log kompensasi ke LogManager (FR2) dan pastikan persisten (WAL)
            LogRecord clr = logManager.logCompensation(txId, record.lsn());
            logManager.ensureDurable(clr.lsn());

            if (hasDataUndo) {
                DataChange change = record.change().get();
                storageManager.writeAt(change.tableName(), change.rid(), change.before(), clr.lsn());
            } else {
                // 2. Ekstrak before-image skema dari payload log SCHEMA_CHANGE
                SchemaChange schemaChange = record.schemaChange().get();
                List<SchemaImage> beforeImages = schemaChange.before();

                // 3. Panggil restoreSchema melalui SmPort dalam urutan mundur
                for (int i = beforeImages.size() - 1; i >= 0; i--) {
                    storageManager.restoreSchema(beforeImages.get(i), clr.lsn());
                }
            }
            written++;
        }
        }
        return written;
    }

    private static boolean isDataChange(LogType type) {
        return type == LogType.INSERT || type == LogType.UPDATE || type == LogType.DELETE;
    }

    private static boolean has(List<LogRecord> records, LogType type) {
        for (LogRecord record : records) {
            if (record.type() == type) {
                return true;
            }
        }
        return false;
    }
}