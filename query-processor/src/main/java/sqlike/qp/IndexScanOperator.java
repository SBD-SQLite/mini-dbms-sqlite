package sqlike.qp;

import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.qp.port.QpContracts.AccessGrant;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.IndexSearch;
import sqlike.qp.port.QpContracts.Rid;
import sqlike.qp.port.QpContracts.RidCursor;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.shared.ErrorCode;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.storage.StorageException;

/**
 * Physical index scan operator matching CLASS DIAGRAM FIX (Tab 4) and PRD v1.0 (§8, §9).
 */
public final class IndexScanOperator extends AbstractOperator {
    private final IStorageManager storage;
    private final sqlike.qp.port.QpContracts.IStorageManager qpStorage;
    private final IndexScanSpec spec;
    private final TableId tableId;
    private Optional<Iterator<RID>> cursor;
    private Optional<RidCursor> qpCursor;

    public IndexScanOperator(IStorageManager storage, IndexScanSpec spec, Schema schema) {
        super(schema);
        if (storage == null) {
            throw new IllegalArgumentException("Storage manager cannot be null in IndexScanOperator");
        }
        if (spec == null) {
            throw new IllegalArgumentException("IndexScanSpec cannot be null in IndexScanOperator");
        }
        if (spec.tableName() == null || spec.tableName().isBlank()) {
            throw new IllegalArgumentException("Table name cannot be null or blank in IndexScanOperator");
        }
        if (spec.indexName() == null || spec.indexName().isBlank()) {
            throw new IllegalArgumentException("Index name cannot be null or blank in IndexScanOperator");
        }
        this.storage = storage;
        this.qpStorage = null;
        this.spec = spec;
        this.tableId = new TableId(spec.tableName());
        this.cursor = Optional.empty();
        this.qpCursor = Optional.empty();
    }

    public IndexScanOperator(sqlike.qp.port.QpContracts.IStorageManager storage, IndexScanSpec spec, Schema schema) {
        super(schema);
        if (storage == null) {
            throw new IllegalArgumentException("Storage manager cannot be null in IndexScanOperator");
        }
        if (spec == null) {
            throw new IllegalArgumentException("IndexScanSpec cannot be null in IndexScanOperator");
        }
        if (spec.tableName() == null || spec.tableName().isBlank()) {
            throw new IllegalArgumentException("Table name cannot be null or blank in IndexScanOperator");
        }
        if (spec.indexName() == null || spec.indexName().isBlank()) {
            throw new IllegalArgumentException("Index name cannot be null or blank in IndexScanOperator");
        }
        this.storage = null;
        this.qpStorage = storage;
        this.spec = spec;
        this.tableId = new TableId(spec.tableName());
        this.cursor = Optional.empty();
        this.qpCursor = Optional.empty();
    }

    public TableId getTableId() {
        return tableId;
    }

    public IndexScanSpec getSpec() {
        return spec;
    }

    @Override
    protected void doOpen() {
        ExecutionContext ctx = requireContext();
        checkContextActive(ctx);
        try {
            if (qpStorage != null) {
                AccessGrant grant = requireReadGrant(ctx);
                RidCursor rc = qpStorage.findIndex(
                        tableId,
                        spec.indexName(),
                        new IndexSearch(spec.condition().orElse(null)),
                        ctx,
                        grant);
                if (rc == null) {
                    throw new QpException(
                            ErrorCode.IO_ERROR,
                            "Storage manager returned null RID cursor for index: " + spec.indexName());
                }
                this.qpCursor = Optional.of(rc);
                return;
            }
            List<RID> rids = storage.lookupIndex(spec.indexName(), spec.condition().orElse(null));
            if (rids == null) {
                throw new QpException(
                        ErrorCode.IO_ERROR,
                        "Storage manager returned null RID list for index: " + spec.indexName());
            }
            this.cursor = Optional.of(List.copyOf(rids).iterator());
        } catch (QpException e) {
            throw e;
        } catch (StorageException e) {
            throw TableScanOperator.mapStorageException(
                    "Failed to lookup index '" + spec.indexName() + "'", e);
        } catch (UncheckedIOException e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "I/O failure looking up index '" + spec.indexName() + "': " + e.getMessage());
        } catch (Exception e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "Failed to lookup index '" + spec.indexName() + "': " + e.getMessage());
        }
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        ExecutionContext ctx = requireContext();
        checkContextActive(ctx);
        try {
            if (qpStorage != null) {
                RidCursor activeQp = qpCursor.orElseThrow(
                        () -> new QpException(
                                ErrorCode.INVALID_STATE,
                                "IndexScanOperator cursor is not open for index: " + spec.indexName()));
                Optional<Rid> nextRid = activeQp.next();
                if (nextRid.isEmpty()) {
                    doClose();
                    return Optional.empty();
                }
                StoredRecord record = fetchRecord(nextRid.get());
                return Optional.of(RowEnvelope.of(record.row(), record.rid()));
            }
            Iterator<RID> activeCursor = cursor.orElseThrow(
                    () -> new QpException(
                            ErrorCode.INVALID_STATE,
                            "IndexScanOperator cursor is not open for index: " + spec.indexName()));
            if (!activeCursor.hasNext()) {
                doClose();
                return Optional.empty();
            }
            RID nextRid = activeCursor.next();
            StoredRecord record = fetchRecord(nextRid);
            return Optional.of(RowEnvelope.of(record.row(), record.rid()));
        } catch (QpException e) {
            closeOnFailure(e);
            throw e;
        } catch (StorageException e) {
            QpException mapped = TableScanOperator.mapStorageException(
                    "Storage failure during index scan on '" + spec.indexName() + "'", e);
            closeOnFailure(mapped);
            throw mapped;
        } catch (UncheckedIOException e) {
            QpException mapped = new QpException(
                    ErrorCode.IO_ERROR,
                    "Cursor I/O error during index scan on '" + spec.indexName() + "': " + e.getMessage());
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
        this.cursor = Optional.empty();
    }

    private StoredRecord fetchRecord(Rid rid) {
        if (rid == null || rid.value() == null) {
            throw new IllegalArgumentException("Rid cannot be null when fetching record in IndexScanOperator");
        }
        validateRid(rid.value());
        if (qpStorage != null) {
            ExecutionContext ctx = requireContext();
            AccessGrant grant = requireReadGrant(ctx);
            Optional<StoredRecord> fetched = qpStorage.fetch(tableId, rid, ctx, grant);
            return fetched.orElseThrow(() -> new QpException(
                    ErrorCode.NOT_FOUND,
                    "Indexed RID not found in table '" + spec.tableName() + "': " + rid.value()));
        }
        return fetchRecord(rid.value());
    }

    private StoredRecord fetchRecord(RID rid) {
        if (rid == null) {
            throw new IllegalArgumentException("RID cannot be null when fetching record in IndexScanOperator");
        }
        validateRid(rid);
        ExecutionContext ctx = requireContext();
        checkContextActive(ctx);
        try {
            Optional<Record> fetched = storage.fetch(spec.tableName(), rid);
            if (fetched == null || fetched.isEmpty()) {
                throw new QpException(
                        ErrorCode.NOT_FOUND,
                        "Indexed RID not found in table '" + spec.tableName() + "': " + rid);
            }
            Record record = fetched.get();
            return new StoredRecord(record.rid(), Row.of(record.values()));
        } catch (QpException e) {
            throw e;
        } catch (StorageException e) {
            throw TableScanOperator.mapStorageException(
                    "Failed to fetch record for RID " + rid + " in table '" + spec.tableName() + "'", e);
        } catch (UncheckedIOException e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "I/O failure fetching record for RID " + rid + " in table '" + spec.tableName() + "': " + e.getMessage());
        } catch (Exception e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "Failed to fetch record for RID " + rid + " in table '" + spec.tableName() + "': " + e.getMessage());
        }
    }

    private void validateRid(RID rid) {
        if (rid.pageId() == null || rid.pageId().pageNumber() < 0 || rid.slotId() < 0) {
            throw new QpException(
                    ErrorCode.INVALID_VALUE,
                    "Invalid negative or malformed RID in IndexScanOperator: " + rid);
        }
        String ridTable = rid.pageId().tableId();
        if (ridTable != null && !ridTable.isBlank() && !ridTable.equalsIgnoreCase(spec.tableName())) {
            throw new QpException(
                    ErrorCode.INVALID_VALUE,
                    "RID belongs to table '" + ridTable + "', expected '" + spec.tableName() + "'");
        }
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
        String msg = e.getMessage() != null ? e.getMessage() : "Unexpected runtime state in IndexScanOperator";
        if (msg.contains("cancelled") || msg.contains("aborted")) {
            return new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
        }
        return new QpException(ErrorCode.INVALID_STATE, msg);
    }
}
