package sqlike.qp;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.IndexSearch;
import sqlike.qp.port.QpContracts.Rid;
import sqlike.qp.port.QpContracts.RidCursor;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.shared.ErrorCode;
import sqlike.shared.RID;
import sqlike.shared.Record;

/**
 * Physical index scan operator matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class IndexScanOperator extends AbstractOperator {
    private final IStorageManager storage;
    private final sqlike.qp.port.QpContracts.IStorageManager qpStorage;
    private final IndexScanSpec spec;
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
        this.cursor = Optional.empty();
        this.qpCursor = Optional.empty();
    }

    @Override
    protected void doOpen() {
        ExecutionContext ctx = requireContext();
        try {
            if (qpStorage != null) {
                TableId tableId = new TableId(spec.tableName());
                RidCursor rc = qpStorage.findIndex(
                        tableId,
                        spec.indexName(),
                        new IndexSearch(spec.condition().orElse(null)),
                        ctx,
                        ctx.requireGrant(tableId, AccessMode.READ));
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
        } catch (Exception e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "Failed to lookup index '" + spec.indexName() + "': " + e.getMessage());
        }
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        if (qpStorage != null) {
            RidCursor activeQp = qpCursor.orElseThrow(
                    () -> new QpException(
                            ErrorCode.INVALID_STATE,
                            "IndexScanOperator cursor is not open for index: " + spec.indexName()));
            Optional<Rid> nextRid = activeQp.next();
            if (nextRid.isEmpty()) {
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
            return Optional.empty();
        }
        RID nextRid = activeCursor.next();
        StoredRecord record = fetchRecord(nextRid);
        return Optional.of(RowEnvelope.of(record.row(), record.rid()));
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
        if (qpStorage != null) {
            ExecutionContext ctx = requireContext();
            TableId tableId = new TableId(spec.tableName());
            Optional<StoredRecord> fetched = qpStorage.fetch(
                    tableId, rid, ctx, ctx.requireGrant(tableId, AccessMode.READ));
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
        requireContext();
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
        } catch (Exception e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "Failed to fetch record for RID " + rid + " in table '" + spec.tableName() + "': " + e.getMessage());
        }
    }
}
