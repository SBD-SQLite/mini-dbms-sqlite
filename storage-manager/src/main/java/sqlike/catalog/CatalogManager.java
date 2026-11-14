package sqlike.catalog;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.ForeignKeySpec;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexType;
import sqlike.shared.TableSchema;
import sqlike.storage.StorageException;
import static sqlike.storage.StorageErrorCode.*;

public class CatalogManager {
    private static final int MAGIC = 0x53514C43;
    private static final int VERSION = 1;
    Map<String, TableSchema> schemas;
    Map<String, IndexMetadata> indexes;
    private Path catalogFile = Path.of("data", "catalog.bin").toAbsolutePath().normalize();

    public CatalogManager(){
        this.schemas = new HashMap<>();
        this.indexes = new HashMap<>();
    }

    public Map<String, TableSchema> getSchemas() {
        return schemas;
    }

    public void setSchemas(Map<String, TableSchema> schemas) {
        this.schemas = schemas;
    }

    public Map<String, IndexMetadata> getIndexes() {
        return indexes;
    }

    public void setIndexes(Map<String, IndexMetadata> indexes) {
        this.indexes = indexes;
    }

    public CatalogManager(Path catalogFile) {
        this();
        this.catalogFile = Objects.requireNonNull(catalogFile).toAbsolutePath().normalize();
    }

    public Path getCatalogFile() {
        return catalogFile;
    }

    public synchronized List<String> listTables() {
        return schemas.values().stream().map(TableSchema::tableName)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public synchronized TableSchema getSchema(String table) throws StorageException {
        String wanted = key(table);
        TableSchema schema = schemas.values().stream()
                .filter(value -> keyUnchecked(value.tableName()).equals(wanted)).findFirst().orElse(null);
        if (schema == null) throw new StorageException(TABLE_NOT_FOUND, "Table not found: " + table);
        return schema;
    }

    public synchronized boolean tableExists(String table) throws StorageException {
        String wanted = key(table);
        return schemas.values().stream().anyMatch(value -> keyUnchecked(value.tableName()).equals(wanted));
    }

    public synchronized List<IndexMetadata> getIndexes(String table) throws StorageException {
        String name = getSchema(table).tableName();
        return indexes.values().stream().filter(index -> index.tableName().equalsIgnoreCase(name))
                .sorted(Comparator.comparing(IndexMetadata::indexName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    // Called by the offline loader / schema owner, not a substitute for logged SQL CREATE TABLE.
    public synchronized void registerTable(TableSchema schema) throws StorageException {
        TableSchema copy = checkedSchema(schema);
        if (tableExists(copy.tableName())
                || schemas.values().stream().anyMatch(existing -> existing.tableId().equals(copy.tableId()))) {
            throw new StorageException(INVALID_VALUE, "Table name or tableId already exists: " + copy.tableName());
        }
        schemas.put(copy.tableName(), copy);
    }

    public synchronized void registerIndex(IndexMetadata metadata) throws StorageException {
        IndexMetadata copy = checkedIndex(metadata, schemas);
        if (indexes.values().stream().anyMatch(existing -> existing.indexName().equalsIgnoreCase(copy.indexName()))) {
            throw new StorageException(INVALID_VALUE, "Index already exists: " + copy.indexName());
        }
        indexes.put(copy.indexName(), copy);
    }

    public synchronized String generateTableId(String tableName) throws StorageException {
        String base = "tbl_" + key(tableName).replaceAll("[^a-z0-9_-]", "_");
        Set<String> used = new HashSet<>();
        for (TableSchema schema : schemas.values()) used.add(schema.tableId().toLowerCase(Locale.ROOT));
        String candidate = base;
        for (int n = 2; used.contains(candidate); n++) candidate = base + "_" + n;
        return candidate;
    }

    // Removes the index entry only (memory)
    public synchronized IndexMetadata unregisterIndex(String indexName) throws StorageException {
        String wanted = key(indexName);
        String found = indexes.keySet().stream().filter(name -> keyUnchecked(name).equals(wanted)).findFirst().orElse(null);
        if (found == null) throw new StorageException(INDEX_NOT_FOUND, "Index not found: " + indexName);
        return indexes.remove(found);
    }

    // Removes the table and every index on it (memory)
    public synchronized TableSchema unregisterTable(String table) throws StorageException {
        TableSchema schema = getSchema(table);
        for (IndexMetadata index : getIndexes(schema.tableName())) unregisterIndex(index.indexName());
        schemas.remove(schema.tableName());
        return schema;
    }

    // Replaces an existing table's schema (same tableId and name) in memory
    public synchronized void replaceSchema(TableSchema schema) throws StorageException {
        TableSchema copy = checkedSchema(schema);
        TableSchema old = getSchema(copy.tableName());
        if (!old.tableId().equals(copy.tableId())) {
            throw new StorageException(INVALID_VALUE, "tableId cannot change: " + copy.tableName());
        }
        schemas.put(old.tableName(), copy);
    }

    // Other tables whose foreign keys reference (self references excluded)
    public synchronized List<TableSchema> dependents(String table) throws StorageException {
        String name = getSchema(table).tableName();
        return schemas.values().stream()
                .filter(other -> !other.tableName().equalsIgnoreCase(name))
                .filter(other -> other.foreignKeys().stream().anyMatch(fk -> fk.referencedTable().equalsIgnoreCase(name)))
                .sorted(Comparator.comparing(TableSchema::tableName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public synchronized void checkForeignKeyTargets(TableSchema schema) throws StorageException {
        TableSchema checked = checkedSchema(schema);
        for (ForeignKeySpec fk : checked.foreignKeys()) {
            TableSchema target = fk.referencedTable().equalsIgnoreCase(checked.tableName()) ? checked : getSchema(fk.referencedTable());
            for (int i = 0; i < fk.columnNames().size(); i++) {
                ColumnSchema local = column(checked, fk.columnNames().get(i));
                ColumnSchema remote = column(target, fk.referencedColumns().get(i));
                if (remote == null) {
                    throw new StorageException(INVALID_VALUE, "Foreign key references unknown column "
                            + target.tableName() + "." + fk.referencedColumns().get(i));
                }
                if (local.dataType() != remote.dataType()) {
                    throw new StorageException(INVALID_VALUE, "Foreign key type mismatch: " + local.name()
                            + " -> " + target.tableName() + "." + remote.name());
                }
            }
            List<String> wanted = fk.referencedColumns().stream().map(CatalogManager::keyUnchecked).toList();
            List<String> primary = target.primaryKey().stream().map(CatalogManager::keyUnchecked).toList();
            if (!wanted.equals(primary)) {
                throw new StorageException(INVALID_VALUE, "Foreign key must reference the primary key of " + target.tableName());
            }
        }
    }

    private static ColumnSchema column(TableSchema schema, String name) {
        return schema.columns().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public synchronized Map<String, TableSchema> snapshotSchemas() { return new LinkedHashMap<>(schemas); }

    public synchronized Map<String, IndexMetadata> snapshotIndexes() { return new LinkedHashMap<>(indexes); }

    public synchronized void restore(Map<String, TableSchema> savedSchemas, Map<String, IndexMetadata> savedIndexes) {
        schemas = new LinkedHashMap<>(savedSchemas);
        indexes = new LinkedHashMap<>(savedIndexes);
    }

    public synchronized void load() throws StorageException {
        if (Files.notExists(catalogFile)) {
            schemas = new LinkedHashMap<>();
            indexes = new LinkedHashMap<>();
            return;
        }
        // Parse and validate a separate catalog before replacing live state.
        CatalogManager candidate = new CatalogManager(catalogFile);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(catalogFile)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unknown catalog format/version");
            int tables = count(input);
            for (int i = 0; i < tables; i++) {
                String id = input.readUTF();
                String name = input.readUTF();
                List<ColumnSchema> columns = new ArrayList<>();
                int size = count(input);
                for (int j = 0; j < size; j++) {
                    String column = input.readUTF();
                    DataType type = DataType.valueOf(input.readUTF());
                    Optional<Integer> length = input.readBoolean() ? Optional.of(input.readInt()) : Optional.empty();
                    columns.add(new ColumnSchema(column, type, length));
                }
                List<String> primaryKey = strings(input);
                List<ForeignKeySpec> foreignKeys = new ArrayList<>();
                int foreignCount = count(input);
                for (int j = 0; j < foreignCount; j++) {
                    foreignKeys.add(new ForeignKeySpec(strings(input), input.readUTF(), strings(input)));
                }
                candidate.registerTable(new TableSchema(id, name, columns, primaryKey, foreignKeys));
            }
            int indexCount = count(input);
            for (int i = 0; i < indexCount; i++) {
                candidate.registerIndex(new IndexMetadata(input.readUTF(), input.readUTF(), input.readUTF(),
                        IndexType.valueOf(input.readUTF())));
            }
            if (input.read() != -1) throw new IOException("Trailing catalog bytes");
        } catch (IOException | IllegalArgumentException | StorageException e) {
            throw new StorageException(IO_ERROR, "Cannot load catalog: " + catalogFile, e);
        }
        schemas = candidate.schemas;
        indexes = candidate.indexes;
    }

    public synchronized void save() throws StorageException {
        Path temporary = null;
        try {
            CatalogManager validated = new CatalogManager(catalogFile);
            for (TableSchema schema : schemas.values()) validated.registerTable(schema);
            for (IndexMetadata metadata : indexes.values()) validated.registerIndex(metadata);
            Files.createDirectories(catalogFile.getParent());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(schemas.size());
                for (TableSchema schema : schemas.values()) {
                    output.writeUTF(schema.tableId());
                    output.writeUTF(schema.tableName());
                    output.writeInt(schema.columns().size());
                    for (ColumnSchema column : schema.columns()) {
                        output.writeUTF(column.name());
                        output.writeUTF(column.dataType().name());
                        output.writeBoolean(column.maxLength().isPresent());
                        if (column.maxLength().isPresent()) output.writeInt(column.maxLength().get());
                    }
                    strings(output, schema.primaryKey());
                    output.writeInt(schema.foreignKeys().size());
                    for (ForeignKeySpec foreign : schema.foreignKeys()) {
                        strings(output, foreign.columnNames());
                        output.writeUTF(foreign.referencedTable());
                        strings(output, foreign.referencedColumns());
                    }
                }
                output.writeInt(indexes.size());
                for (IndexMetadata index : indexes.values()) {
                    output.writeUTF(index.indexName());
                    output.writeUTF(index.tableName());
                    output.writeUTF(index.columnName());
                    output.writeUTF(index.indexType().name());
                }
            }
            temporary = Files.createTempFile(catalogFile.getParent(), ".catalog-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Fail instead of falling back to a non-atomic replacement of the live catalog.
            Files.move(temporary, catalogFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException(IO_ERROR, "Cannot save catalog: " + catalogFile, e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private static TableSchema checkedSchema(TableSchema schema) throws StorageException {
        if (schema == null || schema.columns() == null || schema.primaryKey() == null || schema.foreignKeys() == null) {
            throw new StorageException(INVALID_VALUE, "Schema fields must not be null");
        }
        key(schema.tableId());
        key(schema.tableName());
        if (schema.columns().isEmpty()) throw new StorageException(INVALID_VALUE, "Table must contain columns");
        List<ColumnSchema> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ColumnSchema column : schema.columns()) {
            if (column == null || column.dataType() == null || column.maxLength() == null
                    || !names.add(key(column.name()))) {
                throw new StorageException(INVALID_VALUE, "Invalid or duplicate column");
            }
            boolean text = column.dataType() == DataType.CHAR || column.dataType() == DataType.VARCHAR;
            if (text ? column.maxLength().isEmpty() || column.maxLength().get() <= 0 : column.maxLength().isPresent()) {
                throw new StorageException(INVALID_VALUE, "Invalid maxLength: " + column.name());
            }
            columns.add(new ColumnSchema(column.name(), column.dataType(), column.maxLength()));
        }
        List<String> primary = checkedColumns(schema.primaryKey(), names);
        List<ForeignKeySpec> foreign = new ArrayList<>();
        for (ForeignKeySpec fk : schema.foreignKeys()) {
            if (fk == null || fk.referencedColumns() == null) throw new StorageException(INVALID_VALUE, "Invalid foreign key");
            key(fk.referencedTable());
            List<String> local = checkedColumns(fk.columnNames(), names);
            if (local.isEmpty() || local.size() != fk.referencedColumns().size()) {
                throw new StorageException(INVALID_VALUE, "Foreign key column counts differ");
            }
            for (String name : fk.referencedColumns()) key(name);
            foreign.add(new ForeignKeySpec(local, fk.referencedTable(), List.copyOf(fk.referencedColumns())));
        }
        // Cross-table FK enforcement belongs to the DDL/DML coordinator, not catalog lookup.
        return new TableSchema(schema.tableId(), schema.tableName(), List.copyOf(columns), primary, List.copyOf(foreign));
    }

    private static List<String> checkedColumns(List<String> columns, Set<String> available) throws StorageException {
        if (columns == null) throw new StorageException(INVALID_VALUE, "Constraint columns must not be null");
        Set<String> unique = new HashSet<>();
        for (String name : columns) {
            String key = key(name);
            if (!available.contains(key) || !unique.add(key)) throw new StorageException(INVALID_VALUE, "Invalid constraint column: " + name);
        }
        return List.copyOf(columns);
    }

    private static IndexMetadata checkedIndex(IndexMetadata index, Map<String, TableSchema> schemas) throws StorageException {
        if (index == null || index.indexType() == null) throw new StorageException(INVALID_VALUE, "Invalid index metadata");
        key(index.indexName());
        String tableKey = key(index.tableName());
        TableSchema schema = schemas.values().stream()
                .filter(value -> keyUnchecked(value.tableName()).equals(tableKey)).findFirst().orElse(null);
        if (schema == null) throw new StorageException(TABLE_NOT_FOUND, "Index table not found: " + index.tableName());
        String wanted = key(index.columnName());
        for (ColumnSchema column : schema.columns()) {
            if (key(column.name()).equals(wanted)) {
                return new IndexMetadata(index.indexName(), schema.tableName(), column.name(), index.indexType());
            }
        }
        throw new StorageException(INVALID_VALUE, "Index column not found: " + index.columnName());
    }

    private static String keyUnchecked(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String key(String name) throws StorageException {
        if (name == null || name.isBlank() || !name.equals(name.strip())) {
            throw new StorageException(INVALID_VALUE, "Identifier must be nonblank without surrounding whitespace");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static int count(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > 1_000_000) throw new IOException("Invalid catalog collection length: " + size);
        return size;
    }

    private static List<String> strings(DataInputStream input) throws IOException {
        int size = count(input);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < size; i++) result.add(input.readUTF());
        return result;
    }

    private static void strings(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) output.writeUTF(value);
    }
}
