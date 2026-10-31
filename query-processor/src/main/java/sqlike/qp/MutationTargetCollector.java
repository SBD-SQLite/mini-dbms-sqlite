package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.ErrorCode;
import sqlike.shared.RID;
import sqlike.shared.Record;

public class MutationTargetCollector {
    private final IStorageManager storage;

    public MutationTargetCollector(IStorageManager storage) {
        this.storage = storage;
    }

    public IStorageManager getStorage() {
        return storage;
    }

    public List<StoredRecord> collect(Operator source, sqlike.qp.port.QpContracts.TableId tableId, ExecutionContext context) {
        if (tableId == null) {
            throw new IllegalArgumentException("Target table cannot be null in MutationTargetCollector");
        }
        return collect(source, tableId.value(), context);
    }

    public List<StoredRecord> collect(Operator source, String tableName, ExecutionContext context) {
        if (source == null) {
            throw new IllegalArgumentException("Source operator cannot be null in MutationTargetCollector");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Target table name cannot be null or blank in MutationTargetCollector");
        }
        if (context == null) {
            throw new IllegalArgumentException("ExecutionContext cannot be null in MutationTargetCollector");
        }
        if (storage == null) {
            throw new QpException(
                    ErrorCode.INVALID_STATE,
                    "IStorageManager is not configured in MutationTargetCollector");
        }

        List<StoredRecord> targets = new ArrayList<>();
        source.open(context);
        try {
            while (true) {
                Optional<RowEnvelope> next = source.next();
                if (next.isEmpty()) {
                    break;
                }
                RowEnvelope envelope = next.get();
                RID rid = requireRid(envelope, tableName);
                try {
                    Optional<Record> fetched = storage.fetch(tableName, rid);
                    if (fetched == null || fetched.isEmpty()) {
                        throw new QpException(
                                ErrorCode.NOT_FOUND,
                                "Target record with RID " + rid + " not found in table '" + tableName + "'");
                    }
                    Record record = fetched.get();
                    targets.add(new StoredRecord(record.rid(), Row.of(record.values())));
                } catch (QpException e) {
                    throw e;
                } catch (Exception e) {
                    throw new QpException(
                            ErrorCode.IO_ERROR,
                            "Failed to fetch mutation target record " + rid + " from table '" + tableName + "': " + e.getMessage());
                }
            }
        } finally {
            source.close();
        }
        return List.copyOf(targets);
    }

    private RID requireRid(RowEnvelope row, String tableName) {
        if (row == null) {
            throw new IllegalArgumentException("RowEnvelope cannot be null in requireRid");
        }
        return row.rid().orElseThrow(
                () -> new QpException(
                        ErrorCode.INVALID_STATE,
                        "RowEnvelope does not carry a physical RID for mutation on table: " + tableName));
    }
}
