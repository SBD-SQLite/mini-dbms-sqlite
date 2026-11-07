package sqlike.qp;

import java.util.*;
import sqlike.shared.*;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;

public class MutationCoordinator {
    private final sqlike.qp.port.QpContracts.IStorageManager storage;
    private final IConcurrencyControl concurrency;
    private final AccessCoordinator access;

    /** Storage-only compatibility constructor. Use a CCM port for WAL-backed writes. */
    public MutationCoordinator(sqlike.contract.IStorageManager storage) {
        this(storage instanceof sqlike.qp.port.QpContracts.IStorageManager qpSm
                ? qpSm
                : (storage != null ? new sqlike.qp.port.StorageAdapter(storage) : null), null);
    }

    public MutationCoordinator(sqlike.qp.port.QpContracts.IStorageManager storage, IConcurrencyControl concurrency) {
        this.storage = storage;
        this.concurrency = concurrency;
        this.access = concurrency == null ? null : new AccessCoordinator(concurrency);
    }

    public MutationCoordinator(sqlike.contract.IStorageManager storage, IConcurrencyControl concurrency) {
        this(storage instanceof sqlike.qp.port.QpContracts.IStorageManager qpSm
                ? qpSm
                : (storage != null ? new sqlike.qp.port.StorageAdapter(storage) : null), concurrency);
    }

    public sqlike.qp.port.QpContracts.IStorageManager getStorage() {
        return storage;
    }

    public Rid insert(TableId tableId, Row row, ExecutionContext context) {
        if (storage == null) throw failure("Storage manager is not initialized in MutationCoordinator");
        AccessGrant writeGrant = context.requireGrant(tableId, AccessMode.WRITE);
        Row values = storage.getSchema(tableId).validate(row);
        enforceInsertOrUpdateConstraints(tableId, values, null, context);
        Rid rid = storage.reserveInsert(tableId, values, context, writeGrant);
        try {
            apply(new ChangeRecord(context.nextChangeId(), new TxId(context.txId), new DataChange(ChangeOperation.INSERT,
                    tableId.value(), rid.value(), new RecordImage(false, List.of()), new RecordImage(true, values.values()))), context);
        } catch (RuntimeException failure) {
            try {
                cleanupReservation(tableId, rid, new TxId(context.txId));
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        cleanupReservation(tableId, rid, new TxId(context.txId));
        return rid;
    }

    public RID insert(String tableName, Row row, ExecutionContext context) {
        return insert(new TableId(tableName), row, context).value();
    }

    public boolean update(TableId tableId, StoredRecord before, Row after, ExecutionContext context) {
        if (storage == null) throw failure("Storage manager is not initialized in MutationCoordinator");
        context.requireGrant(tableId, AccessMode.WRITE);
        Row values = storage.getSchema(tableId).validate(after);
        if (before.row().equals(values)) return false;
        enforceInsertOrUpdateConstraints(tableId, values, before.rid(), context);
        enforceParentReferenceProtection(tableId, before.row(), values, context);
        apply(new ChangeRecord(context.nextChangeId(), new TxId(context.txId), new DataChange(ChangeOperation.UPDATE,
                tableId.value(), before.rid(), new RecordImage(true, before.row().values()), new RecordImage(true, values.values()))), context);
        return true;
    }

    public boolean update(String tableName, StoredRecord before, Row after, ExecutionContext context) {
        return update(new TableId(tableName), before, after, context);
    }

    public void delete(TableId tableId, StoredRecord before, ExecutionContext context) {
        context.requireGrant(tableId, AccessMode.WRITE);
        enforceParentReferenceProtection(tableId, before.row(), null, context);
        apply(new ChangeRecord(context.nextChangeId(), new TxId(context.txId), new DataChange(ChangeOperation.DELETE,
                tableId.value(), before.rid(), new RecordImage(true, before.row().values()), new RecordImage(false, List.of()))), context);
    }

    public void delete(String tableName, StoredRecord before, ExecutionContext context) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name cannot be null or blank in MutationCoordinator.delete");
        }
        delete(new TableId(tableName), before, context);
    }

    private void apply(ChangeRecord change, ExecutionContext context) {
        context.check();
        if (concurrency != null) {
            if (concurrency.getState(new TxId(context.txId)) != TransactionState.ACTIVE) {
                throw failure("Write requires an active transaction");
            }
            WritePermit permit = concurrency.authorizeChange(change);
            if (!change.id().equals(permit.changeId())) {
                throw failure("Mismatched write permit");
            }
            try (WriteLease lease = concurrency.acquireApplyLease(permit)) {
                Objects.requireNonNull(lease);
                context.check();
                if (storage != null) {
                    storage.applyChange(change, permit);
                }
                concurrency.confirmApplied(change.id());
            }
        } else if (storage != null) {
            storage.applyChange(change, new WritePermit(change.id(), new Lsn(0)));
        }
    }

    private void cleanupReservation(TableId tableId, Rid rid, TxId txId) {
        if (storage != null) {
            storage.releaseReservation(tableId, rid, txId);
        }
    }

    private void enforceInsertOrUpdateConstraints(
            TableId tableId,
            Row candidateRow,
            RID updatingRid,
            ExecutionContext context) {
        if (storage == null) {
            return;
        }
        TableSchema tableSchema;
        try {
            tableSchema = storage.getTableSchema(tableId);
        } catch (RuntimeException e) {
            return;
        }
        if (tableSchema == null) {
            return;
        }
        Schema qpSchema = Schema.fromTableSchema(tableSchema);

        if (!tableSchema.primaryKey().isEmpty()) {
            List<Integer> pkOffsets = tableSchema.primaryKey().stream()
                    .map(col -> qpSchema.findColumnOffset(null, col))
                    .toList();
            List<Value> candidatePk = pkOffsets.stream().map(candidateRow::getValue).toList();
            AccessGrant grant = context.requireGrant(tableId, AccessMode.READ);
            try (var cursor = storage.scan(tableId, context, grant)) {
                while (true) {
                    Optional<StoredRecord> next = cursor.next();
                    if (next.isEmpty()) {
                        break;
                    }
                    StoredRecord existing = next.get();
                    if (updatingRid != null && updatingRid.equals(existing.rid())) {
                        continue;
                    }
                    List<Value> existingPk = pkOffsets.stream()
                            .map(existing.row()::getValue)
                            .toList();
                    if (candidatePk.equals(existingPk)) {
                        throw new QpException(
                                ErrorCode.INVALID_VALUE,
                                "PRIMARY KEY constraint violation on table '" + tableId.value() + "'");
                    }
                }
            }
        }

        for (ForeignKeySpec fk : tableSchema.foreignKeys()) {
            TableId refTableId = new TableId(fk.referencedTable());
            TableSchema refTableSchema = storage.getTableSchema(refTableId);
            Schema refQpSchema = Schema.fromTableSchema(refTableSchema);
            List<Integer> localOffsets = fk.columnNames().stream()
                    .map(col -> qpSchema.findColumnOffset(null, col))
                    .toList();
            List<Integer> refOffsets = fk.referencedColumns().stream()
                    .map(col -> refQpSchema.findColumnOffset(null, col))
                    .toList();
            List<Value> fkValues = localOffsets.stream().map(candidateRow::getValue).toList();

            // The candidate row can satisfy its own foreign key before it is stored.
            if (refTableId.value().equalsIgnoreCase(tableId.value())) {
                List<Value> selfRefValues = refOffsets.stream().map(candidateRow::getValue).toList();
                if (fkValues.equals(selfRefValues)) {
                    continue;
                }
            }

            AccessGrant refGrant = ensureReadGrant(context, refTableId);
            boolean parentFound = false;
            try (var cursor = storage.scan(refTableId, context, refGrant)) {
                while (true) {
                    Optional<StoredRecord> next = cursor.next();
                    if (next.isEmpty()) {
                        break;
                    }
                    StoredRecord parentRec = next.get();
                    List<Value> parentKey = refOffsets.stream()
                            .map(parentRec.row()::getValue)
                            .toList();
                    if (fkValues.equals(parentKey)) {
                        parentFound = true;
                        break;
                    }
                }
            }
            if (!parentFound) {
                throw new QpException(
                        ErrorCode.INVALID_VALUE,
                        "FOREIGN KEY constraint violation on table '" + tableId.value()
                                + "' referencing '" + fk.referencedTable() + "'");
            }
        }
    }

    private void enforceParentReferenceProtection(
            TableId parentTableId,
            Row beforeRow,
            Row afterRow,
            ExecutionContext context) {
        if (storage == null) {
            return;
        }
        List<String> allTables;
        try {
            allTables = storage.listTables();
        } catch (RuntimeException e) {
            return;
        }
        if (allTables == null || allTables.isEmpty()) {
            return;
        }
        TableSchema parentSchema;
        try {
            parentSchema = storage.getTableSchema(parentTableId);
        } catch (RuntimeException e) {
            return;
        }
        if (parentSchema == null) {
            return;
        }
        Schema parentQpSchema = Schema.fromTableSchema(parentSchema);

        for (String childTableName : allTables) {
            TableId childTableId = new TableId(childTableName);
            TableSchema childSchema = storage.getTableSchema(childTableId);
            if (childSchema == null || childSchema.foreignKeys().isEmpty()) {
                continue;
            }
            Schema childQpSchema = Schema.fromTableSchema(childSchema);
            for (ForeignKeySpec fk : childSchema.foreignKeys()) {
                if (!fk.referencedTable().equalsIgnoreCase(parentTableId.value())) {
                    continue;
                }
                List<Integer> parentOffsets = fk.referencedColumns().stream()
                        .map(col -> parentQpSchema.findColumnOffset(null, col))
                        .toList();
                List<Value> oldParentKey = parentOffsets.stream().map(beforeRow::getValue).toList();
                if (afterRow != null) {
                    List<Value> newParentKey = parentOffsets.stream().map(afterRow::getValue).toList();
                    if (oldParentKey.equals(newParentKey)) {
                        continue;
                    }
                }
                List<Integer> childOffsets = fk.columnNames().stream()
                        .map(col -> childQpSchema.findColumnOffset(null, col))
                        .toList();
                AccessGrant childGrant = ensureReadGrant(context, childTableId);
                try (var cursor = storage.scan(childTableId, context, childGrant)) {
                    while (true) {
                        Optional<StoredRecord> next = cursor.next();
                        if (next.isEmpty()) {
                            break;
                        }
                        StoredRecord childRec = next.get();
                        List<Value> childFkVals = childOffsets.stream()
                                .map(childRec.row()::getValue)
                                .toList();
                        if (oldParentKey.equals(childFkVals)) {
                            throw new QpException(
                                    ErrorCode.INVALID_VALUE,
                                    "Cannot modify/delete parent row in '" + parentTableId.value()
                                            + "': referenced by FOREIGN KEY in '" + childTableName + "'");
                        }
                    }
                }
            }
        }
    }

    private AccessGrant ensureReadGrant(ExecutionContext context, TableId tableId) {
        // Foreign-key checks can discover tables outside the statement's planned grants.
        try {
            return context.requireGrant(tableId, AccessMode.READ);
        } catch (IllegalStateException missing) {
            context.check();
            if (access == null) throw missing;
            AccessGrant grant = access.acquireOne(ResourceKey.table(tableId), AccessMode.READ, context);
            context.addGrant(grant);
            return grant;
        }
    }
}
