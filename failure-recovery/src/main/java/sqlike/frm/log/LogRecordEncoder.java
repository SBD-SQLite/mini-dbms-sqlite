package sqlike.frm.log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import sqlike.shared.*;
import sqlike.frm.checkpoint.CheckpointInfo;

// Mengubah satu catatan log menjadi byte, lalu membacanya kembali.
final class LogRecordEncoder {
    private static final int VERSION = 1;

    static byte[] encode(LogRecord record) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);

        out.writeInt(VERSION);
        out.writeLong(record.lsn());
        writeTime(out, record.time());
        writeText(out, record.type().name());
        out.writeLong(record.txID().orElse(0L));
        out.writeLong(record.undoOfLSN().orElse(0L));

        out.writeBoolean(record.change().isPresent());
        if (record.change().isPresent()) {
            writeChange(out, record.change().get());
        }

        out.writeBoolean(record.checkpoint().isPresent());
        if (record.checkpoint().isPresent()) {
            CheckpointInfo checkpoint = record.checkpoint().get();
            out.writeLong(checkpoint.checkpointLSN());
            out.writeLong(checkpoint.flushedThroughLSN());
            writeTime(out, checkpoint.createdAt());
            writeText(out, checkpoint.snapshotId());
        }

        out.writeBoolean(record.schemaChange().isPresent());
        if (record.schemaChange().isPresent()) {
            SchemaChange change = record.schemaChange().get();
            writeSchemas(out, change.before());
            writeSchemas(out, change.after());
        }

        out.flush();
        return buffer.toByteArray();
    }

    static LogRecord decode(byte[] data) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != VERSION) {
                throw new IOException("Unsupported log version");
            }

            long lsn = in.readLong();
            Instant time = readTime(in);
            LogType type = LogType.valueOf(readText(in));
            long txID = in.readLong();
            long undoLSN = in.readLong();

            Optional<DataChange> change = Optional.empty();
            if (in.readBoolean()) {
                change = Optional.of(readChange(in));
            }

            Optional<CheckpointInfo> checkpoint = Optional.empty();
            if (in.readBoolean()) {
                long checkpointLSN = in.readLong();
                long flushedLSN = in.readLong();
                Instant createdAt = readTime(in);
                String snapshotId = readText(in);
                checkpoint = Optional.of(new CheckpointInfo(checkpointLSN, flushedLSN, createdAt, snapshotId));
            }

            Optional<SchemaChange> schema = Optional.empty();
            if (in.readBoolean()) {
                List<SchemaImage> before = readSchemas(in);
                List<SchemaImage> after = readSchemas(in);
                schema = Optional.of(new SchemaChange(before, after));
            }

            if (in.available() != 0) {
                throw new IOException("Unexpected bytes at the end of the log record");
            }

            return new LogRecord(lsn, time, txID == 0 ? Optional.empty() : Optional.of(txID),
                    type, change, checkpoint, schema, undoLSN == 0 ? Optional.empty() : Optional.of(undoLSN));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid log record", e);
        }
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.write(data);
    }

    private static String readText(DataInputStream in) throws IOException {
        int size = readCount(in);
        byte[] data = new byte[size];
        in.readFully(data);
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > in.available()) {
            throw new IOException("Invalid log data length");
        }
        return count;
    }

    private static void writeTime(DataOutputStream out, Instant time) throws IOException {
        out.writeLong(time.getEpochSecond());
        out.writeInt(time.getNano());
    }

    private static Instant readTime(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        if (nanos < 0 || nanos >= 1_000_000_000) {
            throw new IOException("Invalid log timestamp nanoseconds");
        }
        try {
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (java.time.DateTimeException e) {
            throw new IOException("Invalid log timestamp", e);
        }
    }

    private static void writeChange(DataOutputStream out, DataChange change) throws IOException {
        writeText(out, change.operation().name());
        writeText(out, change.tableName());
        writeText(out, change.rid().pageId().tableId());
        out.writeLong(change.rid().pageId().pageNumber());
        out.writeInt(change.rid().slotId());
        writeImage(out, change.before());
        writeImage(out, change.after());
    }

    private static DataChange readChange(DataInputStream in) throws IOException {
        ChangeOperation operation = ChangeOperation.valueOf(readText(in));
        String table = readText(in);
        String tableId = readText(in);
        long page = in.readLong();
        int slot = in.readInt();
        RID rid = new RID(new PageId(tableId, page), slot);
        RecordImage before = readImage(in);
        RecordImage after = readImage(in);
        return new DataChange(operation, table, rid, before, after);
    }

    private static void writeImage(DataOutputStream out, RecordImage image) throws IOException {
        out.writeBoolean(image.exists());
        out.writeInt(image.values().size());
        for (Value value : image.values()) {
            writeText(out, value.type().name());
            switch (value.type()) {
                case INTEGER -> out.writeInt(value.integerValue().orElseThrow());
                case FLOAT -> out.writeDouble(value.floatValue().orElseThrow());
                case CHAR, VARCHAR -> writeText(out, value.textValue().orElseThrow());
            }
        }
    }

    private static RecordImage readImage(DataInputStream in) throws IOException {
        boolean exists = in.readBoolean();
        int count = readCount(in);
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            DataType type = DataType.valueOf(readText(in));
            Optional<Integer> number = Optional.empty();
            Optional<Double> decimal = Optional.empty();
            Optional<String> text = Optional.empty();
            switch (type) {
                case INTEGER -> number = Optional.of(in.readInt());
                case FLOAT -> decimal = Optional.of(in.readDouble());
                case CHAR, VARCHAR -> text = Optional.of(readText(in));
            }
            values.add(new Value(type, number, decimal, text));
        }
        return new RecordImage(exists, values);
    }

    private static void writeStrings(DataOutputStream out, List<String> items) throws IOException {
        out.writeInt(items.size());
        for (String item : items) {
            writeText(out, item);
        }
    }

    private static List<String> readStrings(DataInputStream in) throws IOException {
        int count = readCount(in);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(readText(in));
        }
        return items;
    }

    private static void writeSchema(DataOutputStream out, TableSchema table) throws IOException {
        writeText(out, table.tableId());
        writeText(out, table.tableName());
        out.writeInt(table.columns().size());
        for (ColumnSchema column : table.columns()) {
            writeText(out, column.name());
            writeText(out, column.dataType().name());
            out.writeInt(column.maxLength().orElse(-1));
        }
        writeStrings(out, table.primaryKey());
        out.writeInt(table.foreignKeys().size());
        for (ForeignKeySpec key : table.foreignKeys()) {
            writeStrings(out, key.columnNames());
            writeText(out, key.referencedTable());
            writeStrings(out, key.referencedColumns());
        }
    }

    private static TableSchema readSchema(DataInputStream in) throws IOException {
        String id = readText(in);
        String name = readText(in);
        int count = readCount(in);
        List<ColumnSchema> columns = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String columnName = readText(in);
            DataType type = DataType.valueOf(readText(in));
            int length = in.readInt();
            Optional<Integer> maxLength = length == -1 ? Optional.empty() : Optional.of(length);
            columns.add(new ColumnSchema(columnName, type, maxLength));
        }
        List<String> primaryKey = readStrings(in);
        int keyCount = readCount(in);
        List<ForeignKeySpec> keys = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            List<String> columnNames = readStrings(in);
            String table = readText(in);
            List<String> referencedColumns = readStrings(in);
            keys.add(new ForeignKeySpec(columnNames, table, referencedColumns));
        }
        return new TableSchema(id, name, columns, primaryKey, keys);
    }

    private static void writeSchemas(DataOutputStream out, List<SchemaImage> images) throws IOException {
        out.writeInt(images.size());
        for (SchemaImage image : images) {
            writeText(out, image.tableName());
            out.writeBoolean(image.schema().isPresent());
            if (image.schema().isPresent()) {
                writeSchema(out, image.schema().get());
            }
            out.writeInt(image.indexes().size());
            for (IndexMetadata index : image.indexes()) {
                writeText(out, index.indexName());
                writeText(out, index.tableName());
                writeText(out, index.columnName());
                writeText(out, index.indexType().name());
            }
            out.writeBoolean(image.snapshotId().isPresent());
            if (image.snapshotId().isPresent()) {
                writeText(out, image.snapshotId().get());
            }
        }
    }

    private static List<SchemaImage> readSchemas(DataInputStream in) throws IOException {
        int count = readCount(in);
        List<SchemaImage> images = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = readText(in);
            Optional<TableSchema> schema = Optional.empty();
            if (in.readBoolean()) {
                schema = Optional.of(readSchema(in));
            }
            int indexCount = readCount(in);
            List<IndexMetadata> indexes = new ArrayList<>();
            for (int j = 0; j < indexCount; j++) {
                String indexName = readText(in);
                String table = readText(in);
                String column = readText(in);
                IndexType type = IndexType.valueOf(readText(in));
                indexes.add(new IndexMetadata(indexName, table, column, type));
            }
            Optional<String> snapshot = Optional.empty();
            if (in.readBoolean()) {
                snapshot = Optional.of(readText(in));
            }
            images.add(new SchemaImage(name, schema, indexes, snapshot));
        }
        return images;
    }
}
