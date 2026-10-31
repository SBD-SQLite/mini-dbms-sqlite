package sqlike.qp.stub;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.qp.Schema;
import sqlike.qp.port.StorageAdapter.TransactionScope;
import sqlike.qp.port.QpContracts.TxId;
import static sqlike.qp.port.QpContracts.*;

/** STUB ONLY: isolated SM fake with transaction overlays, snapshot persistence, and map indexes. */
public final class StubStorage implements IStorageManager, TransactionScope {
    private final Path file;
    private final ThreadLocal<TxId> current = new ThreadLocal<>();
    private final Map<String, TableSchema> schemas = new LinkedHashMap<>();
    private Map<RID, List<Value>> rows = new LinkedHashMap<>();
    private final Map<TxId, Map<RID, Optional<List<Value>>>> changes = new HashMap<>();
    private final Map<TxId, List<IndexMetadata>> newIndexes = new HashMap<>();
    private List<IndexMetadata> definitions = new ArrayList<>();
    private final Map<String, Map<Value, Set<RID>>> indexes = new HashMap<>();
    private final Set<String> reservations = new HashSet<>();
    public final List<String> trace;
    public int scans, lookups, fetches;
    public boolean failApply, failPersist;
    private int nextRid;
    private long lastTx;
    public StubStorage(Path file, List<String> trace) {
        this.file = file; this.trace = trace;
        if (Files.exists(file)) load();
        try { if (Files.exists(sequenceFile())) lastTx = Math.max(lastTx, Long.parseLong(Files.readString(sequenceFile()))); }
        catch (IOException | NumberFormatException e) { throw failure("Invalid stub transaction sequence"); }
        rebuild();
    }
    @Override public void useTransaction(TxId tx) { current.set(tx); }
    private TxId tx() { return Objects.requireNonNull(current.get(), "Stub requires a transaction scope"); }
    public long lastTxId() { return lastTx; }
    private Path sequenceFile() { return file.resolveSibling(file.getFileName() + ".txid"); }
    public synchronized void recordTx(long id) {
        lastTx = Math.max(lastTx, id);
        try { Files.createDirectories(file.toAbsolutePath().getParent()); Files.writeString(sequenceFile(), Long.toString(lastTx)); }
        catch (IOException e) { throw failure("Cannot save stub transaction sequence"); }
    }
    public void define(TableSchema schema) { schemas.put(schema.tableName(), schema); save(rows, definitions); }
    private Map<RID, Optional<List<Value>>> pending(TxId tx) { return changes.getOrDefault(tx, Map.of()); }
    private Optional<List<Value>> read(RID rid) { return pending(tx()).getOrDefault(rid, Optional.ofNullable(rows.get(rid))); }
    @Override public TableSchema getSchema(String table) {
        return Optional.ofNullable(schemas.get(table)).orElseThrow(() -> failure("Unknown table: " + table));
    }
    @Override public RecordCursor scan(String table) {
        getSchema(table); scans++; trace.add("SM.scan");
        var visible = new LinkedHashMap<>(rows);
        pending(tx()).forEach((rid, value) -> { if (value.isPresent()) visible.put(rid, value.get()); else visible.remove(rid); });
        var iterator = visible.entrySet().stream().filter(e -> e.getKey().pageId().tableId().equals(table)).iterator();
        return new RecordCursor() {
            private boolean closed;
            @Override public boolean hasNext() { return !closed && iterator.hasNext(); }
            @Override public Record next() { var e = iterator.next(); return new Record(e.getKey(), e.getValue()); }
            @Override public void close() { closed = true; trace.add("SM.scan.close"); }
        };
    }
    @Override public Optional<Record> fetch(String table, RID rid) {
        getSchema(table); fetches++; trace.add("SM.fetch");
        if (!rid.pageId().tableId().equals(table)) throw failure("RID belongs to another table");
        return read(rid).map(values -> new Record(rid, values));
    }
    @Override public InsertReservation reserveInsert(String table, List<Value> values) {
        getSchema(table); String id = tx().value() + ":" + nextRid;
        var reservation = new InsertReservation(id, table, new RID(new PageId(table, 0), nextRid++), values);
        reservations.add(id); trace.add("SM.reserve"); return reservation;
    }
    private void stage(RID rid, Optional<List<Value>> values, String operation, long lsn) {
        if (lsn <= 0) throw failure("Write requires a logged LSN");
        changes.computeIfAbsent(tx(), key -> new LinkedHashMap<>()).put(rid, values.map(List::copyOf)); trace.add(operation);
        if (failApply) { failApply = false; throw failure("Simulated SM apply failure"); }
    }
    @Override public RID insert(InsertReservation reservation, long lsn) {
        if (!reservations.contains(reservation.reservationId())) throw failure("Unknown reservation");
        stage(reservation.rid(), Optional.of(reservation.values()), "SM.insert", lsn); return reservation.rid();
    }
    @Override public void update(String table, RID rid, List<Value> values, long lsn) {
        if (fetch(table, rid).isEmpty()) throw failure("Update record missing"); stage(rid, Optional.of(values), "SM.update", lsn);
    }
    @Override public void delete(String table, RID rid, long lsn) {
        if (fetch(table, rid).isEmpty()) throw failure("Delete record missing"); stage(rid, Optional.empty(), "SM.delete", lsn);
    }
    @Override public void releaseReservation(InsertReservation reservation) { reservations.remove(reservation.reservationId()); }
    public int reservationCount() { return reservations.size(); }
    @Override public void restoreRecord(String table, RID rid, RecordImage image, long lsn) {
        throw failure("STUB ONLY: rollback uses overlays, not physical recovery");
    }
    @Override public List<String> listTables() { return List.copyOf(schemas.keySet()); }
    @Override public TableStatistics getStatistics(String table) {
        getSchema(table); return new TableStatistics(rows.keySet().stream().filter(r -> r.pageId().tableId().equals(table)).count(), 1, 0, 128, Map.of());
    }
    @Override public List<IndexMetadata> getIndexes(String table) { return definitions.stream().filter(i -> i.tableName().equals(table)).toList(); }
    @Override public void createIndex(IndexMetadata definition) {
        new Schema(getSchema(definition.tableName())).offset(ColumnRef.of(definition.tableName(), definition.columnName()));
        if (definitions.stream().anyMatch(i -> i.indexName().equals(definition.indexName()))
                || newIndexes.values().stream().flatMap(Collection::stream).anyMatch(i -> i.indexName().equals(definition.indexName())))
            throw failure("Index already exists: " + definition.indexName());
        newIndexes.computeIfAbsent(tx(), key -> new ArrayList<>()).add(definition); trace.add("SM.createIndex." + definition.indexType());
    }
    @Override public List<RID> lookupIndex(String name, IndexCondition condition) {
        lookups++; trace.add("SM.lookupIndex");
        var definition = definitions.stream().filter(i -> i.indexName().equals(name)).findFirst().orElseThrow(() -> failure("Unknown index: " + name));
        if (definition.indexType() == IndexType.HASH && condition.operator() != IndexOperator.EQ) throw failure("Hash supports equality only");
        var schema = new Schema(getSchema(definition.tableName()));
        int offset = schema.offset(ColumnRef.of(definition.tableName(), definition.columnName()));
        var found = new LinkedHashSet<RID>();
        var index = indexes.get(name);
        if (condition.operator() == IndexOperator.EQ) found.addAll(index.getOrDefault(schema.coerce(offset, condition.key()), Set.of()));
        else index.forEach((key, matches) -> { if (matches(key, condition)) found.addAll(matches); });
        pending(tx()).forEach((rid, value) -> {
            if (rid.pageId().tableId().equals(definition.tableName())) {
                found.remove(rid); if (value.isPresent() && matches(value.get().get(offset), condition)) found.add(rid);
            }
        });
        return List.copyOf(found);
    }
    private boolean matches(Value value, IndexCondition condition) {
        Object a = payload(value), b = payload(condition.key());
        int order = a instanceof Number x && b instanceof Number y ? Double.compare(x.doubleValue(), y.doubleValue()) : a.toString().compareTo(b.toString());
        return switch (condition.operator()) {
            case EQ -> order == 0; case GT -> order > 0; case GE -> order >= 0; case LT -> order < 0; case LE -> order <= 0;
            case BETWEEN -> throw failure("STUB ONLY: BETWEEN is not needed by QP5 checks");
        };
    }
    public synchronized void commit(TxId tx) {
        var copy = new LinkedHashMap<>(rows);
        pending(tx).forEach((rid, value) -> { if (value.isPresent()) copy.put(rid, value.get()); else copy.remove(rid); });
        var defs = new ArrayList<>(definitions); defs.addAll(newIndexes.getOrDefault(tx, List.of()));
        save(copy, defs); rows = copy; definitions = defs; discard(tx); rebuild(); trace.add("SM.persisted");
    }
    public void discard(TxId tx) {
        changes.remove(tx); newIndexes.remove(tx); reservations.removeIf(id -> id.startsWith(tx.value() + ":"));
    }
    private void rebuild() {
        indexes.clear();
        for (var definition : definitions) {
            int offset = new Schema(getSchema(definition.tableName())).offset(ColumnRef.of(definition.tableName(), definition.columnName()));
            Map<Value, Set<RID>> index = new HashMap<>();
            rows.forEach((rid, values) -> { if (rid.pageId().tableId().equals(definition.tableName()))
                index.computeIfAbsent(values.get(offset), key -> new LinkedHashSet<>()).add(rid); });
            indexes.put(definition.indexName(), index);
        }
    }
    @Override public void flushAll() { save(rows, definitions); }
    private void save(Map<RID, List<Value>> records, List<IndexMetadata> defs) {
        if (failPersist) { failPersist = false; throw failure("Simulated SM persistence failure"); }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (var out = new DataOutputStream(Files.newOutputStream(temporary))) {
                out.writeUTF("QP5_SM_STUB_1"); out.writeLong(lastTx); out.writeInt(nextRid); out.writeInt(schemas.size());
                for (var table : schemas.values()) {
                    out.writeUTF(table.tableName()); out.writeUTF(table.tableId()); out.writeInt(table.columns().size());
                    for (var column : table.columns()) { out.writeUTF(column.name()); out.writeUTF(column.dataType().name()); out.writeInt(column.maxLength().orElse(0)); }
                }
                out.writeInt(records.size());
                for (var e : records.entrySet()) {
                    out.writeUTF(e.getKey().pageId().tableId()); out.writeLong(e.getKey().pageId().pageNumber()); out.writeInt(e.getKey().slotId());
                    for (var value : e.getValue()) out.writeUTF(payload(value).toString());
                }
                out.writeInt(defs.size());
                for (var index : defs) { out.writeUTF(index.indexName()); out.writeUTF(index.tableName()); out.writeUTF(index.columnName()); out.writeUTF(index.indexType().name()); }
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw failure("Stub snapshot write failed: " + e.getMessage()); }
    }
    private void load() {
        try (var in = new DataInputStream(Files.newInputStream(file))) {
            if (!in.readUTF().equals("QP5_SM_STUB_1")) throw new IOException("Bad snapshot header");
            lastTx = in.readLong(); nextRid = in.readInt(); int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF(); String id = in.readUTF(); int columns = in.readInt(); var fields = new ArrayList<ColumnSchema>();
                for (int c = 0; c < columns; c++) {
                    String column = in.readUTF(); DataType type = DataType.valueOf(in.readUTF()); int length = in.readInt();
                    fields.add(new ColumnSchema(column, type, length == 0 ? Optional.empty() : Optional.of(length)));
                }
                schemas.put(name, new TableSchema(id, name, fields));
            }
            count = in.readInt();
            for (int i = 0; i < count; i++) {
                String table = in.readUTF(); RID rid = new RID(new PageId(table, in.readLong()), in.readInt()); var values = new ArrayList<Value>();
                for (var column : getSchema(table).columns()) {
                    String value = in.readUTF();
                    values.add(switch (column.dataType()) {
                        case INTEGER -> sqlike.qo.Literals.integer(Integer.parseInt(value)); case FLOAT -> number(Double.parseDouble(value));
                        case CHAR, VARCHAR -> new Value(column.dataType(), Optional.empty(), Optional.empty(), Optional.of(value));
                    });
                }
                rows.put(rid, List.copyOf(values));
            }
            count = in.readInt();
            for (int i = 0; i < count; i++) definitions.add(new IndexMetadata(in.readUTF(), in.readUTF(), in.readUTF(), IndexType.valueOf(in.readUTF())));
        } catch (IOException | IllegalArgumentException e) { throw failure("Stub snapshot read failed: " + e.getMessage()); }
    }
    @Override public void close() { }
}
