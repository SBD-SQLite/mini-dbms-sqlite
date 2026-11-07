package sqlike.qp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.IConcurrencyControl;
import sqlike.qp.port.QpContracts.SchemaChangeRecord;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.qp.port.QpContracts.TxId;
import sqlike.qp.port.QpContracts.WriteLease;
import sqlike.qp.port.QpContracts.WritePermit;
import sqlike.shared.ColumnSchema;
import sqlike.shared.CreateTableSpec;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ForeignKeySpec;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import sqlike.shared.TableSchema;
import sqlike.storage.StorageException;

/**
 * Physical command executor for CREATE TABLE (QP-15* / PRD §12).
 * Validates table name, column definitions, primary key, and foreign key references,
 * logs SchemaChange when concurrency control is present, and creates the table in Storage Manager.
 */
public final class CreateTableExecutor extends AbstractCommandExecutor {
    private final CreateTableSpec spec;
    private final sqlike.contract.IStorageManager storageContract;
    private final sqlike.qp.port.QpContracts.IStorageManager qpStorage;
    private final IConcurrencyControl concurrency;

    public CreateTableExecutor(CreateTableSpec spec, sqlike.contract.IStorageManager storage) {
        this(spec, Objects.requireNonNull(storage), null, null);
    }

    public CreateTableExecutor(
            CreateTableSpec spec,
            sqlike.qp.port.QpContracts.IStorageManager qpStorage,
            IConcurrencyControl concurrency) {
        this(spec, null, Objects.requireNonNull(qpStorage), concurrency);
    }

    private CreateTableExecutor(
            CreateTableSpec spec,
            sqlike.contract.IStorageManager storageContract,
            sqlike.qp.port.QpContracts.IStorageManager qpStorage,
            IConcurrencyControl concurrency) {
        super();
        this.spec = Objects.requireNonNull(spec, "CreateTableSpec cannot be null");
        this.storageContract = storageContract;
        this.qpStorage = qpStorage;
        this.concurrency = concurrency;
    }

    public CreateTableSpec getSpec() {
        return spec;
    }

    @Override
    protected long doExecute(ExecutionContext context) {
        if (!context.implicit()) {
            throw new QpException(
                    ErrorCode.INVALID_STATE,
                    "CREATE TABLE is not allowed within an explicit transaction");
        }
        TableSchema newSchema = validateAndBuildSchema();
        TableId tableId = new TableId(newSchema.tableName());
        var grant = context.requireGrant(tableId, AccessMode.WRITE);

        SchemaImage beforeImg = new SchemaImage(newSchema.tableName(), Optional.empty(), List.of(), Optional.empty());
        SchemaImage afterImg = new SchemaImage(newSchema.tableName(), Optional.of(newSchema), List.of(), Optional.empty());
        SchemaChange schemaChange = new SchemaChange(List.of(beforeImg), List.of(afterImg));

        if (concurrency != null && qpStorage != null) {
            SchemaChangeRecord record = new SchemaChangeRecord(
                    context.nextChangeId(), new TxId(context.txId), schemaChange);
            WritePermit permit = concurrency.authorizeSchemaChange(record);
            try (WriteLease lease = concurrency.acquireApplyLease(permit)) {
                Objects.requireNonNull(lease);
                context.check();
                qpStorage.createTable(newSchema, context, grant, permit.lsn().value());
                concurrency.confirmApplied(record.id());
            }
        } else if (qpStorage != null) {
            qpStorage.createTable(newSchema, context, grant, 1L);
        } else if (storageContract != null) {
            try {
                storageContract.createTable(newSchema, 1L);
            } catch (StorageException e) {
                throw new QpException(ErrorCode.IO_ERROR, "Failed to create table: " + e.getMessage());
            }
        }
        return 0L;
    }

    private TableSchema validateAndBuildSchema() {
        String tableName = spec.tableName() != null ? spec.tableName().trim() : "";
        if (tableName.isEmpty() || tableName.equalsIgnoreCase("__catalog__")) {
            throw new QpException(ErrorCode.SEMANTIC_ERROR, "Invalid or reserved table name: '" + tableName + "'");
        }

        List<String> existingTables = listTables();
        for (String existing : existingTables) {
            if (existing.equalsIgnoreCase(tableName)) {
                throw new QpException(ErrorCode.SEMANTIC_ERROR, "Table '" + tableName + "' already exists");
            }
        }

        List<ColumnSchema> cols = spec.columns();
        if (cols == null || cols.isEmpty()) {
            throw new QpException(ErrorCode.SEMANTIC_ERROR, "CREATE TABLE requires at least one column");
        }

        Set<String> seenColumns = new HashSet<>();
        for (ColumnSchema col : cols) {
            if (col == null || col.name() == null || col.name().isBlank()) {
                throw new QpException(ErrorCode.SEMANTIC_ERROR, "Column name cannot be empty");
            }
            if (col.dataType() == null) {
                throw new QpException(ErrorCode.SEMANTIC_ERROR, "Column '" + col.name() + "' has null DataType");
            }
            String norm = col.name().trim().toLowerCase(Locale.ROOT);
            if (!seenColumns.add(norm)) {
                throw new QpException(ErrorCode.SEMANTIC_ERROR, "Duplicate column name: '" + col.name() + "'");
            }
            if (col.dataType() == DataType.CHAR || col.dataType() == DataType.VARCHAR) {
                if (col.maxLength().isEmpty() || col.maxLength().get() <= 0) {
                    throw new QpException(
                            ErrorCode.SEMANTIC_ERROR,
                            "Column '" + col.name() + "' of type " + col.dataType() + " requires positive maxLength");
                }
            } else {
                if (col.maxLength().isPresent()) {
                    throw new QpException(
                            ErrorCode.SEMANTIC_ERROR,
                            "Numeric column '" + col.name() + "' must not specify maxLength");
                }
            }
        }

        List<String> canonicalPk = new ArrayList<>();
        Set<String> seenPk = new HashSet<>();
        for (String pkCol : spec.primaryKey()) {
            ColumnSchema matched = findColumn(cols, pkCol)
                    .orElseThrow(() -> new QpException(
                            ErrorCode.SEMANTIC_ERROR,
                            "Primary key column '" + pkCol + "' does not exist in table '" + tableName + "'"));
            String norm = matched.name().toLowerCase(Locale.ROOT);
            if (!seenPk.add(norm)) {
                throw new QpException(ErrorCode.SEMANTIC_ERROR, "Duplicate column '" + pkCol + "' in PRIMARY KEY");
            }
            canonicalPk.add(matched.name());
        }

        TableSchema tentativeSelfSchema = new TableSchema(tableName, tableName, cols, canonicalPk, List.of());
        List<ForeignKeySpec> canonicalFks = new ArrayList<>();
        for (ForeignKeySpec fk : spec.foreignKeys()) {
            if (fk == null
                    || fk.columnNames() == null
                    || fk.columnNames().isEmpty()
                    || fk.referencedColumns() == null
                    || fk.referencedColumns().size() != fk.columnNames().size()
                    || fk.referencedTable() == null
                    || fk.referencedTable().isBlank()) {
                throw new QpException(
                        ErrorCode.SEMANTIC_ERROR,
                        "Invalid FOREIGN KEY specification on table '" + tableName + "'");
            }

            TableSchema refSchema;
            if (fk.referencedTable().equalsIgnoreCase(tableName)) {
                refSchema = tentativeSelfSchema;
            } else {
                String actualRefName = existingTables.stream()
                        .filter(t -> t.equalsIgnoreCase(fk.referencedTable()))
                        .findFirst()
                        .orElseThrow(() -> new QpException(
                                ErrorCode.SEMANTIC_ERROR,
                                "Referenced table '" + fk.referencedTable() + "' does not exist"));
                refSchema = getTableSchema(actualRefName);
            }

            if (!refSchema.primaryKey().isEmpty()) {
                if (refSchema.primaryKey().size() != fk.referencedColumns().size()) {
                    throw new QpException(
                            ErrorCode.SEMANTIC_ERROR,
                            "FOREIGN KEY column count does not match PRIMARY KEY of '" + refSchema.tableName() + "'");
                }
                for (int i = 0; i < refSchema.primaryKey().size(); i++) {
                    if (!refSchema.primaryKey().get(i).equalsIgnoreCase(fk.referencedColumns().get(i))) {
                        throw new QpException(
                                ErrorCode.SEMANTIC_ERROR,
                                "FOREIGN KEY referenced columns must match PRIMARY KEY order of '"
                                        + refSchema.tableName() + "'");
                    }
                }
            }

            List<String> localNames = new ArrayList<>();
            List<String> refNames = new ArrayList<>();
            for (int i = 0; i < fk.columnNames().size(); i++) {
                String localColName = fk.columnNames().get(i);
                String refColName = fk.referencedColumns().get(i);
                ColumnSchema localCol = findColumn(cols, localColName)
                        .orElseThrow(() -> new QpException(
                                ErrorCode.SEMANTIC_ERROR,
                                "FOREIGN KEY column '" + localColName + "' not found in '" + tableName + "'"));
                ColumnSchema refCol = findColumn(refSchema.columns(), refColName)
                        .orElseThrow(() -> new QpException(
                                ErrorCode.SEMANTIC_ERROR,
                                "Referenced column '" + refColName + "' not found in '" + refSchema.tableName() + "'"));
                if (localCol.dataType() != refCol.dataType()
                        || !Objects.equals(localCol.maxLength(), refCol.maxLength())) {
                    throw new QpException(
                            ErrorCode.SEMANTIC_ERROR,
                            "FOREIGN KEY column '" + localCol.name() + "' type does not match referenced column '"
                                    + refSchema.tableName() + "." + refCol.name() + "'");
                }
                localNames.add(localCol.name());
                refNames.add(refCol.name());
            }
            canonicalFks.add(new ForeignKeySpec(localNames, refSchema.tableName(), refNames));
        }

        return new TableSchema(tableName, tableName, cols, canonicalPk, canonicalFks);
    }

    private static Optional<ColumnSchema> findColumn(List<ColumnSchema> cols, String name) {
        if (name == null) {
            return Optional.empty();
        }
        return cols.stream().filter(c -> c.name().equalsIgnoreCase(name.trim())).findFirst();
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

    @Override
    protected void doClose() {
    }
}

