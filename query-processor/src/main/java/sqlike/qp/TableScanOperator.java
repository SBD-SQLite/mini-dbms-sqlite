package sqlike.qp;

import java.io.UncheckedIOException;
import java.util.NoSuchElementException;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.qp.port.QpContracts.AccessGrant;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.shared.ErrorCode;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.ScanSpec;
import sqlike.storage.StorageErrorCode;
import sqlike.storage.StorageException;

/**
 * Physical sequential table scan operator matching CLASS DIAGRAM FIX (Tab 4) and PRD v1.0 (§8, §9).
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
        this.relationId = spec.alias().filter(a -> !a.isBlank()).orElse(spec.tableName());
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
        this.relationId = spec.alias().filter(a -> !a.isBlank()).orElse(spec.tableName());
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
        checkContextActive(ctx);
        try {
            if (qpStorage != null) {
                AccessGrant grant = requireReadGrant(ctx);
                var c = qpStorage.scan(tableId, ctx, grant);
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
        } catch (StorageException e) {
            throw mapStorageException("Failed to open table scan for table '" + tableName + "'", e);
        } catch (UncheckedIOException e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "I/O failure opening table scan for table '" + tableName + "': " + e.getMessage());
        } catch (Exception e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "Failed to open table scan for table '" + tableName + "': " + e.getMessage());
        }
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        ExecutionContext ctx = requireContext();
        checkContextActive(ctx);
        try {
            if (qpStorage != null) {
                var activeQpCursor = qpCursor.orElseThrow(
                        () -> new QpException(
                                ErrorCode.INVALID_STATE,
                                "TableScanOperator cursor is not open for table: " + tableName));
                Optional<StoredRecord> nextRecord = activeQpCursor.next();
                if (nextRecord.isEmpty()) {
                    doClose();
                    return Optional.empty();
                }
                return Optional.of(wrap(nextRecord.get()));
            }
            RecordCursor activeCursor = cursor.orElseThrow(
                    () -> new QpException(
                            ErrorCode.INVALID_STATE,
                            "TableScanOperator cursor is not open for table: " + tableName));
            if (!activeCursor.hasNext()) {
                doClose();
                return Optional.empty();
            }
            Record record = activeCursor.next();
            if (record == null) {
                doClose();
                return Optional.empty();
            }
            StoredRecord stored = new StoredRecord(record.rid(), Row.of(record.values()));
            return Optional.of(wrap(stored));
        } catch (QpException e) {
            closeOnFailure(e);
            throw e;
        } catch (StorageException e) {
            QpException mapped = mapStorageException("Storage failure during table scan on '" + tableName + "'", e);
            closeOnFailure(mapped);
            throw mapped;
        } catch (UncheckedIOException e) {
            QpException mapped = new QpException(
                    ErrorCode.IO_ERROR,
                    "Cursor I/O error during table scan on '" + tableName + "': " + e.getMessage());
            closeOnFailure(mapped);
            throw mapped;
        } catch (NoSuchElementException | IllegalStateException e) {
            QpException mapped = mapRuntimeException(e);
            closeOnFailure(mapped);
            throw mapped;
        } catch (RuntimeException e) {
            closeOnFailure(e);
            throw e;
        }
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

    private AccessGrant requireReadGrant(ExecutionContext ctx) {
        try {
            return ctx.requireGrant(tableId, AccessMode.READ);
        } catch (IllegalStateException e) {
            throw mapRuntimeException(e);
        }
    }

    private void checkContextActive(ExecutionContext ctx) {
        try {
            ctx.check();
        } catch (IllegalStateException e) {
            QpException mapped = mapRuntimeException(e);
            closeOnFailure(mapped);
            throw mapped;
        }
    }

    private void closeOnFailure(RuntimeException primary) {
        try {
            doClose();
        } catch (RuntimeException closeErr) {
            if (primary != null && closeErr != primary) {
                primary.addSuppressed(closeErr);
            }
        }
    }

    private static QpException mapRuntimeException(RuntimeException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "Unexpected runtime state in TableScanOperator";
        if (msg.contains("cancelled") || msg.contains("aborted")) {
            return new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
        }
        return new QpException(ErrorCode.INVALID_STATE, msg);
    }

    static QpException mapStorageException(String prefix, StorageException e) {
        StorageErrorCode code = e.getCode();
        ErrorCode mapped = switch (code != null ? code : StorageErrorCode.IO_ERROR) {
            case TABLE_NOT_FOUND, RECORD_NOT_FOUND, INDEX_NOT_FOUND -> ErrorCode.NOT_FOUND;
            case INVALID_VALUE -> ErrorCode.INVALID_VALUE;
            case INVALID_STATE, INVALID_RESERVATION -> ErrorCode.INVALID_STATE;
            case UNSUPPORTED_OPERATION -> ErrorCode.UNSUPPORTED_OPERATION;
            case WAL_FAILURE -> ErrorCode.WAL_FAILURE;
            case IO_ERROR -> ErrorCode.IO_ERROR;
        };
        return new QpException(mapped, prefix + ": " + e.getMessage());
    }
}
