package sqlike.qp;

import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.shared.ErrorCode;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.ScanSpec;

/**
 * Physical sequential table scan operator matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class TableScanOperator extends AbstractOperator {
    private final IStorageManager storage;
    private final sqlike.qp.port.QpContracts.IStorageManager qpStorage;
    private final TableId tableId;
    private final String relationId;
    private final String tableName;
    private Optional<RecordCursor> cursor;
    private Optional<sqlike.qp.port.QpContracts.RecordCursor> qpCursor;

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
        this.qpStorage = null;
        this.tableName = spec.tableName();
        this.tableId = new TableId(spec.tableName());
        this.relationId = spec.tableName();
        this.cursor = Optional.empty();
        this.qpCursor = Optional.empty();
    }

    public TableScanOperator(sqlike.qp.port.QpContracts.IStorageManager storage, ScanSpec spec, Schema schema) {
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
        this.storage = null;
        this.qpStorage = storage;
        this.tableName = spec.tableName();
        this.tableId = new TableId(spec.tableName());
        this.relationId = spec.tableName();
        this.cursor = Optional.empty();
        this.qpCursor = Optional.empty();
    }

    public TableId getTableId() {
        return tableId;
    }

    public String getRelationId() {
        return relationId;
    }

    @Override
    protected void doOpen() {
        ExecutionContext ctx = requireContext();
        try {
            if (qpStorage != null) {
                var c = qpStorage.scan(tableId, ctx, ctx.requireGrant(tableId, AccessMode.READ));
                if (c == null) {
                    throw new QpException(
                            ErrorCode.IO_ERROR,
                            "Storage manager returned null cursor for table: " + tableName);
                }
                this.qpCursor = Optional.of(c);
                return;
            }
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
        if (qpStorage != null) {
            var activeQpCursor = qpCursor.orElseThrow(
                    () -> new QpException(
                            ErrorCode.INVALID_STATE,
                            "TableScanOperator cursor is not open for table: " + tableName));
            return activeQpCursor.next().map(this::wrap);
        }
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
        if (qpCursor.isPresent()) {
            try {
                qpCursor.get().close();
            } finally {
                qpCursor = Optional.empty();
            }
        }
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
