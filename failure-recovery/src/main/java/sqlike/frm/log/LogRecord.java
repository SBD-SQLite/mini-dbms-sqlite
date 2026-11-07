package sqlike.frm.log;

import java.time.Instant;
import java.util.*;
import sqlike.shared.*;
import sqlike.frm.checkpoint.CheckpointInfo;

public record LogRecord(long lsn, Instant time, Optional<Long> txID, LogType type, Optional<DataChange> change, Optional<CheckpointInfo> checkpoint, Optional<SchemaChange> schemaChange, Optional<Long> undoOfLSN){
    public LogRecord(long lsn, Instant time, Optional<Long> txID, LogType type, Optional<DataChange> change, Optional<CheckpointInfo> checkpoint){
        this(lsn, time, txID, type, change, checkpoint, Optional.empty(), Optional.empty());
    }
    
    public LogRecord{
        if (lsn <= 0) throw new IllegalArgumentException("LSN must be positive!");

        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(txID, "txID");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(checkpoint, "checkpoint");   
        Objects.requireNonNull(schemaChange, "schemaChange");
        Objects.requireNonNull(undoOfLSN, "undoOfLSN");

        if (txID.isPresent() && txID.get() <= 0) {
            throw new IllegalArgumentException("Invalid txID!");
        }

        change = change.map(LogRecord::copyChange);
        schemaChange = schemaChange.map(s -> new SchemaChange(s.before().stream().map(LogRecord::copySchema).toList(), s.after().stream().map(LogRecord::copySchema).toList()));
        boolean data = change.isPresent();
        boolean schema = schemaChange.isPresent();

        switch (type){
            case CHECKPOINT ->{
                if (txID.isPresent() || data || schema || checkpoint.isEmpty() || checkpoint.get().checkpointLSN() != lsn){
                    throw new IllegalArgumentException("Invalid checkpoint record");
                }
            }
            case BEGIN, COMMIT, ABORT ->{
                if (txID.isEmpty() || data || schema || checkpoint.isPresent()){
                    throw new IllegalArgumentException("Invalid transaction marker");
                }
            }
            case INSERT, UPDATE, DELETE -> {
                if (txID.isEmpty() || !data || schema || checkpoint.isPresent() || !type.name().equals(change.get().operation().name())){
                    throw new IllegalArgumentException("Invalid data record");
                }
            }
            case SCHEMA_CHANGE -> {
                if (txID.isEmpty() || data || !schema || checkpoint.isPresent()){
                    throw new IllegalArgumentException("Invalid schema record");
                }
            }
            case COMPENSATION -> {
                if (txID.isEmpty() || data == schema || checkpoint.isPresent() || undoOfLSN.isEmpty() || undoOfLSN.get() <= 0 || undoOfLSN.get() >= lsn){
                    throw new IllegalArgumentException("Invalid compensation record");
                }
            }
        }
        if (type != LogType.COMPENSATION && undoOfLSN.isPresent()){
            throw new IllegalArgumentException("Only compensation refers to undo LSN");
        }
    }
    private static DataChange copyChange(DataChange c){
        Objects.requireNonNull(c.operation()); 
        Objects.requireNonNull(c.rid());
        if (c.tableName() == null || c.tableName().isBlank() || c.rid().slotId() < 0 || c.rid().pageId().pageNumber() < 0 || c.rid().pageId().tableId().isBlank()){
            throw new IllegalArgumentException("Invalid record identity");
        }
        RecordImage before = copyImage(c.before()), after = copyImage(c.after());
        boolean valid = 
            switch (c.operation()) {
                case INSERT -> !before.exists() && after.exists();
                case DELETE -> before.exists() && !after.exists();
                case UPDATE -> before.exists() && after.exists();
        };
        if (!valid) throw new IllegalArgumentException("Invalid before/after image for operation " + c.operation());
        return new DataChange(c.operation(), c.tableName(), c.rid(), before, after);
    }

    private static RecordImage copyImage(RecordImage img){
        var values = List.copyOf(img.values());
        if (!img.exists() && !values.isEmpty()) throw new IllegalArgumentException("Absent record has values");

        for (Value v : values) {
            int count = (v.integerValue().isPresent() ? 1 : 0) + (v.floatValue().isPresent() ? 1 : 0) + (v.textValue().isPresent() ? 1 : 0);
            boolean valid = 
                switch (v.type()) {
                    case INTEGER -> v.integerValue().isPresent();
                    case FLOAT -> v.floatValue().isPresent() && Double.isFinite(v.floatValue().get());
                    case CHAR, VARCHAR -> v.textValue().isPresent();
                };
            if (count != 1 || !valid) throw new IllegalArgumentException("Invalid typed value");
        }
        return new RecordImage(img.exists(), values);
    }

    private static SchemaImage copySchema(SchemaImage img) {
        Optional<TableSchema> schema = img.schema().map(s -> new TableSchema(s.tableId(), s.tableName(),
                List.copyOf(s.columns()), List.copyOf(s.primaryKey()), s.foreignKeys().stream()
                .map(f -> new ForeignKeySpec(List.copyOf(f.columnNames()), f.referencedTable(), List.copyOf(f.referencedColumns()))).toList()));
        return new SchemaImage(img.tableName(), schema, List.copyOf(img.indexes()), img.snapshotId());
    }
}