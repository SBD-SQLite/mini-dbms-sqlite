package sqlike.qp;

import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.ErrorCode;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.ScanSpec;

public final class TableScanOperator extends AbstractOperator {
    private final IStorageManager storage;
    private final String tableName;
    private Optional<RecordCursor> cursor;

    public TableScanOperator(IStorageManager storage, ScanSpec spec, Schema schema) {
        super(schema);
        if (storage == null) {
            throw new IllegalArgumentException("Storage manager cannot be null in TableScanOperator");
        }
        if (spec == null) {
            throw new IllegalArgumentException("ScanSpec cannot be null in TableScanOperator");
        }
        if (spec.tableName() == null || spec.tableName().isBlank()) {
            throw new IllegalArgumentException("Table name cannot be null or blank in TableScanOperator");
        }
        this.storage = storage;
        this.tableName = spec.tableName();
        this.cursor = Optional.empty();
    }

    @Override
    protected void doOpen() {
        requireContext();
        try {
            RecordCursor recordCursor = storage.scan(tableName);
            if (recordCursor == null) {
                throw new QpException(
                        ErrorCode.IO_ERROR,
                        "Storage manager returned null cursor for table: " + tableName);
            }
            this.cursor = Optional.of(recordCursor);
        } catch (QpException e) {
            throw e;
        } catch (Exception e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "Failed to open table scan for table '" + tableName + "': " + e.getMessage());
        }
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        RecordCursor activeCursor = cursor.orElseThrow(
                () -> new QpException(
                        ErrorCode.INVALID_STATE,
                        "TableScanOperator cursor is not open for table: " + tableName));
        if (!activeCursor.hasNext()) {
            return Optional.empty();
        }
        Record record = activeCursor.next();
        if (record == null) {
            return Optional.empty();
        }
        StoredRecord stored = new StoredRecord(record.rid(), Row.of(record.values()));
        return Optional.of(wrap(stored));
    }

    @Override
    protected void doClose() {
        if (cursor.isPresent()) {
            try {
                cursor.get().close();
            } finally {
                cursor = Optional.empty();
            }
        }
    }

    private RowEnvelope wrap(StoredRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("StoredRecord cannot be null when wrapping to RowEnvelope");
        }
        return RowEnvelope.of(record.row(), record.rid());
    }
}
