package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.IConcurrencyControl;
import sqlike.qp.port.QpContracts.SchemaChangeRecord;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.qp.port.QpContracts.TxId;
import sqlike.qp.port.QpContracts.WriteLease;
import sqlike.qp.port.QpContracts.WritePermit;
import sqlike.shared.DropBehavior;
import sqlike.shared.DropTableSpec;
import sqlike.shared.ErrorCode;
import sqlike.shared.ForeignKeySpec;
import sqlike.shared.IndexMetadata;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.storage.StorageException;

/**
 * Physical command executor for DROP TABLE <tableName> [RESTRICT | CASCADE] (QP-15* / PRD §12).
 * Enforces RESTRICT against foreign key dependents and removes referencing ForeignKeySpecs on CASCADE.
 */
public final class DropTableExecutor extends AbstractCommandExecutor {
    private final DropTableSpec spec;
    private final sqlike.contract.IStorageManager storageContract;
    private final sqlike.qp.port.QpContracts.IStorageManager qpStorage;
    private final IConcurrencyControl concurrency;

    public DropTableExecutor(DropTableSpec spec, sqlike.contract.IStorageManager storage) {
        this(spec, Objects.requireNonNull(storage), null, null);
    }

    public DropTableExecutor(
            DropTableSpec spec,
            sqlike.qp.port.QpContracts.IStorageManager qpStorage,
            IConcurrencyControl concurrency) {
        this(spec, null, Objects.requireNonNull(qpStorage), concurrency);
    }

    private DropTableExecutor(
            DropTableSpec spec,
            sqlike.contract.IStorageManager storageContract,
            sqlike.qp.port.QpContracts.IStorageManager qpStorage,
            IConcurrencyControl concurrency) {
        super();
        this.spec = Objects.requireNonNull(spec, "DropTableSpec cannot be null");
        this.storageContract = storageContract;
        this.qpStorage = qpStorage;
        this.concurrency = concurrency;
    }

    public DropTableSpec getSpec() {
        return spec;
    }

    @Override
    protected long doExecute(ExecutionContext context) {
        if (!context.implicit()) {
            throw new QpException(
                    ErrorCode.INVALID_STATE,
                    "DROP TABLE is not allowed within an explicit transaction");
        }
        String requestedName = spec.tableName() != null ? spec.tableName().trim() : "";
        if (requestedName.isEmpty()) {
            throw new QpException(ErrorCode.SEMANTIC_ERROR, "DROP TABLE requires a valid table name");
        }

        List<String> existingTables = listTables();
        String targetTable = existingTables.stream()
                .filter(t -> t.equalsIgnoreCase(requestedName))
                .findFirst()
                .orElseThrow(() -> new QpException(
                        ErrorCode.SEMANTIC_ERROR,
                        "Unknown table '" + requestedName + "' in DROP TABLE"));

        TableSchema targetSchema = getTableSchema(targetTable);
        DropBehavior behavior = spec.behavior() != null ? spec.behavior() : DropBehavior.RESTRICT;

        List<SchemaImage> beforeImages = new ArrayList<>();
        List<SchemaImage> afterImages = new ArrayList<>();

        String snapshotId = createSnapshot();
        beforeImages.add(new SchemaImage(
                targetSchema.tableName(),
                Optional.of(targetSchema),
                getIndexes(targetSchema.tableName()),
                Optional.ofNullable(snapshotId)));
        afterImages.add(new SchemaImage(
                targetSchema.tableName(),
                Optional.empty(),
                List.of(),
                Optional.empty()));

        for (String otherName : existingTables) {
            if (otherName.equalsIgnoreCase(targetTable)) {
                continue; // Self-reference is not an external dependency (PRD §12)
            }
            TableSchema otherSchema = getTableSchema(otherName);
            boolean referencesTarget = otherSchema.foreignKeys().stream()
                    .anyMatch(fk -> fk.referencedTable().equalsIgnoreCase(targetTable));
            if (referencesTarget) {
                if (behavior == DropBehavior.RESTRICT) {
                    throw new QpException(
                            ErrorCode.SEMANTIC_ERROR,
                            "Cannot DROP TABLE '" + targetTable + "' with RESTRICT: referenced by foreign key in '"
                                    + otherSchema.tableName() + "'");
                }
                List<ForeignKeySpec> remainingFks = otherSchema.foreignKeys().stream()
                        .filter(fk -> !fk.referencedTable().equalsIgnoreCase(targetTable))
                        .toList();
                TableSchema updatedOther = new TableSchema(
                        otherSchema.tableId(),
                        otherSchema.tableName(),
                        otherSchema.columns(),
                        otherSchema.primaryKey(),
                        remainingFks);
                List<IndexMetadata> otherIndexes = getIndexes(otherSchema.tableName());
                beforeImages.add(new SchemaImage(
                        otherSchema.tableName(),
                        Optional.of(otherSchema),
                        otherIndexes,
                        Optional.empty()));
                afterImages.add(new SchemaImage(
                        otherSchema.tableName(),
                        Optional.of(updatedOther),
                        otherIndexes,
                        Optional.empty()));
            }
        }

        TableId tableId = new TableId(targetTable);
        var grant = context.requireGrant(tableId, AccessMode.WRITE);
        SchemaChange schemaChange = new SchemaChange(beforeImages, afterImages);

        if (concurrency != null && qpStorage != null) {
            SchemaChangeRecord record = new SchemaChangeRecord(
                    context.nextChangeId(), new TxId(context.txId), schemaChange);
            WritePermit permit = concurrency.authorizeSchemaChange(record);
            try (WriteLease lease = concurrency.acquireApplyLease(permit)) {
                Objects.requireNonNull(lease);
                context.check();
                qpStorage.dropTable(tableId, behavior, context, grant, permit.lsn().value());
                concurrency.confirmApplied(record.id());
            }
        } else if (qpStorage != null) {
            qpStorage.dropTable(tableId, behavior, context, grant, 1L);
        } else if (storageContract != null) {
            try {
                storageContract.dropTable(targetTable, behavior, 1L);
            } catch (StorageException e) {
                throw new QpException(ErrorCode.IO_ERROR, "Failed to drop table: " + e.getMessage());
            }
        }
        return 0L;
    }

    private List<String> listTables() {
        if (qpStorage != null) {
            return qpStorage.listTables();
        }
        if (storageContract != null) {
            return storageContract.listTables();
        }
        return List.of();
    }

    private TableSchema getTableSchema(String table) {
        if (qpStorage != null) {
            return qpStorage.getTableSchema(new TableId(table));
        }
        if (storageContract != null) {
            return storageContract.getSchema(table);
        }
        throw new QpException(ErrorCode.SEMANTIC_ERROR, "Unknown table: " + table);
    }

    private List<IndexMetadata> getIndexes(String table) {
        if (qpStorage != null) {
            return qpStorage.getIndexes(new TableId(table));
        }
        if (storageContract != null) {
            return storageContract.getIndexes(table);
        }
        return List.of();
    }

    private String createSnapshot() {
        if (qpStorage != null) {
            return qpStorage.createSnapshot();
        }
        if (storageContract != null) {
            return storageContract.createSnapshot();
        }
        return "snap-0";
    }

    @Override
    protected void doClose() {
    }
}

