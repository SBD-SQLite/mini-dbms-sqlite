package sqlike.frm.adapter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import sqlike.contract.IStorageManager;
import sqlike.frm.FrmException;
import sqlike.frm.port.SmPort;
import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.shared.RecordImage;
import sqlike.storage.StorageException;

/**
 * Plugs the real Storage Manager into FRM's {@link SmPort} (DIP, PRD v1.0 §1).
 *
 * <p>The adapter only maps names and types; it never changes contract semantics:
 * <ul>
 *   <li>{@code readAt} uses {@code fetch}; an empty result becomes {@code exists=false}.</li>
 *   <li>{@code writeAt} uses {@code restoreRecord}, which keeps the RID and creates no new WAL.</li>
 *   <li>Checked {@link StorageException}s become {@link FrmException}s from component SM, so FRM
 *       never continues after a failed write.</li>
 * </ul>
 */
public class StorageManagerAdapter implements SmPort {

    private final IStorageManager storage;

    public StorageManagerAdapter(IStorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public RecordImage readAt(String table, RID rid) {
        try {
            Optional<Record> record = storage.fetch(table, rid);
            return record
                    .map(found -> new RecordImage(true, List.copyOf(found.values())))
                    .orElseGet(() -> new RecordImage(false, List.of()));
        } catch (StorageException error) {
            throw failure("fetch " + table + " " + rid, error);
        }
    }

    @Override
    public void writeAt(String table, RID rid, RecordImage image, long lsn) {
        try {
            storage.restoreRecord(table, rid, image, lsn);
        } catch (StorageException error) {
            throw failure("restoreRecord " + table + " " + rid + " at LSN " + lsn, error);
        }
    }

    @Override
    public void flushAll() {
        try {
            storage.flushAll();
        } catch (StorageException error) {
            throw failure("flushAll", error);
        }
    }

    @Override
    public String createSnapshot() {
        try {
            return storage.createSnapshot();
        } catch (StorageException error) {
            throw failure("createSnapshot", error);
        }
    }

    @Override
    public void restoreSnapshot(String snapshotId) {
        try {
            storage.restoreSnapshot(snapshotId);
        } catch (StorageException error) {
            throw failure("restoreSnapshot " + snapshotId, error);
        }
    }

    private static FrmException failure(String operation, StorageException error) {
        ErrorCode code = error.getCode() == null ? ErrorCode.IO_ERROR : switch (error.getCode()) {
            case TABLE_NOT_FOUND, RECORD_NOT_FOUND, INDEX_NOT_FOUND -> ErrorCode.NOT_FOUND;
            case INVALID_VALUE, INVALID_RESERVATION -> ErrorCode.INVALID_VALUE;
            case INVALID_STATE -> ErrorCode.INVALID_STATE;
            case UNSUPPORTED_OPERATION -> ErrorCode.UNSUPPORTED_OPERATION;
            case WAL_FAILURE -> ErrorCode.WAL_FAILURE;
            case IO_ERROR -> ErrorCode.IO_ERROR;
        };
        return new FrmException(code, ComponentType.SM,
                "SM " + operation + " failed: " + error.getMessage(), error);
    }
}
