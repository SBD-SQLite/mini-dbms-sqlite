package sqlike.qp;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.qp.port.QpContracts.AccessGrant;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.Rid;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.shared.ErrorCode;
import sqlike.shared.RID;
import sqlike.shared.Record;
import sqlike.storage.StorageException;

/**
 * Eagerly materializes and deduplicates target StoredRecords (before-images) prior to
 * physical UPDATE/DELETE writes to prevent the Halloween Problem (QP3 — Milestone 2 / PRD §8, §10).
 */
public class MutationTargetCollector {
    private final IStorageManager storage;
    private final sqlike.qp.port.QpContracts.IStorageManager qpStorage;

    public MutationTargetCollector(IStorageManager storage) {
        this(storage, null);
    }

    public static MutationTargetCollector withQpStorage(sqlike.qp.port.QpContracts.IStorageManager qpStorage) {
        return new MutationTargetCollector(null, qpStorage);
    }

    private MutationTargetCollector(
            IStorageManager storage,
            sqlike.qp.port.QpContracts.IStorageManager qpStorage) {
        this.storage = storage;
        this.qpStorage = qpStorage;
    }

    public IStorageManager getStorage() {
        return storage;
    }

    public sqlike.qp.port.QpContracts.IStorageManager getQpStorage() {
        return qpStorage;
    }

    public List<StoredRecord> collect(Operator source, TableId tableId, ExecutionContext context) {
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
        if (storage == null && qpStorage == null) {
            throw new QpException(
                    ErrorCode.INVALID_STATE,
                    "IStorageManager is not configured in MutationTargetCollector");
        }

        checkContextActive(context);
        Map<RID, StoredRecord> targets = new LinkedHashMap<>();
        TableId tableId = new TableId(tableName);
        source.open(context);
        try {
            while (true) {
                checkContextActive(context);
                Optional<RowEnvelope> next = source.next();
                if (next.isEmpty()) {
                    break;
                }
                RowEnvelope envelope = next.get();
                RID rid = requireRid(envelope, tableName);
                if (targets.containsKey(rid)) {
                    continue;
                }
                targets.put(rid, fetchBeforeImage(tableId, tableName, rid, context));
            }
        } finally {
            source.close();
        }
        return List.copyOf(targets.values());
    }

    private StoredRecord fetchBeforeImage(
            TableId tableId,
            String tableName,
            RID rid,
            ExecutionContext context) {
        try {
            if (qpStorage != null) {
                AccessGrant grant = requireReadGrant(context, tableId);
                Optional<StoredRecord> fetched = qpStorage.fetch(tableId, new Rid(rid), context, grant);
                if (fetched == null || fetched.isEmpty()) {
                    throw new QpException(
                            ErrorCode.NOT_FOUND,
                            "Target record with RID " + rid + " not found in table '" + tableName + "'");
                }
                return fetched.get();
            }
            Optional<Record> fetched = storage.fetch(tableName, rid);
            if (fetched == null || fetched.isEmpty()) {
                throw new QpException(
                        ErrorCode.NOT_FOUND,
                        "Target record with RID " + rid + " not found in table '" + tableName + "'");
            }
            Record record = fetched.get();
            return new StoredRecord(record.rid(), Row.of(record.values()));
        } catch (QpException e) {
            throw e;
        } catch (StorageException e) {
            throw TableScanOperator.mapStorageException(
                    "Failed to fetch mutation target record " + rid + " from table '" + tableName + "'", e);
        } catch (UncheckedIOException e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "I/O failure fetching mutation target record " + rid + " from table '" + tableName + "': " + e.getMessage());
        } catch (Exception e) {
            throw new QpException(
                    ErrorCode.IO_ERROR,
                    "Failed to fetch mutation target record " + rid + " from table '" + tableName + "': " + e.getMessage());
        }
    }

    private Rid requireRid(RowEnvelope row, TableId tableId) {
        return new Rid(requireRid(row, tableId != null ? tableId.value() : ""));
    }

    private RID requireRid(RowEnvelope row, String tableName) {
        if (row == null) {
            throw new IllegalArgumentException("RowEnvelope cannot be null in requireRid");
        }
        RID rid = row.rid().orElseThrow(
                () -> new QpException(
                        ErrorCode.INVALID_STATE,
                        "RowEnvelope does not carry a physical RID for mutation on table: " + tableName));
        if (rid.pageId() == null || rid.pageId().pageNumber() < 0 || rid.slotId() < 0) {
            throw new QpException(
                    ErrorCode.INVALID_VALUE,
                    "Invalid negative or malformed RID for mutation on table '" + tableName + "': " + rid);
        }
        String ridTable = rid.pageId().tableId();
        if (ridTable != null && !ridTable.isBlank() && !ridTable.equalsIgnoreCase(tableName)) {
            throw new QpException(
                    ErrorCode.INVALID_VALUE,
                    "Target RID belongs to table '" + ridTable + "', expected '" + tableName + "'");
        }
        return rid;
    }

    private AccessGrant requireReadGrant(ExecutionContext context, TableId tableId) {
        try {
            return context.requireGrant(tableId, AccessMode.READ);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Missing READ grant in MutationTargetCollector";
            if (msg.contains("cancelled") || msg.contains("aborted")) {
                throw new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
            }
            throw new QpException(ErrorCode.INVALID_STATE, msg);
        }
    }

    private void checkContextActive(ExecutionContext context) {
        try {
            context.check();
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Transaction cancelled in MutationTargetCollector";
            throw new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
        }
    }
}
