package sqlike.qp;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.ErrorCode;
import sqlike.shared.RID;
import sqlike.shared.Record;

public final class IndexScanOperator extends AbstractOperator {
    private final IStorageManager storage;
    private final IndexScanSpec spec;
    private Optional<Iterator<RID>> cursor;

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
        this.spec = spec;
        this.cursor = Optional.empty();
    }

    @Override
    protected void doOpen() {
        requireContext();
        try {
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
        this.cursor = Optional.empty();
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
