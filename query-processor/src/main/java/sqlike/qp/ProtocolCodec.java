package sqlike.qp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sqlike.qp.port.QpContracts.DbmsError;
import sqlike.shared.ColumnSchema;
import sqlike.shared.ComponentType;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.Value;

/**
 * Binary framing codec for SQL queries and result frames over TCP sockets.
 */
public final class ProtocolCodec {
    public ProtocolCodec() {
    }

    public Optional<String> readQuery(DataInputStream input) {
        if (input == null) {
            throw new IllegalArgumentException("DataInputStream cannot be null in readQuery");
        }
        try {
            int len;
            try {
                len = input.readInt();
            } catch (EOFException e) {
                return Optional.empty();
            }
            if (len < 0) {
                return Optional.empty();
            }
            byte[] bytes = input.readNBytes(len);
            if (bytes.length < len) {
                throw new EOFException("Truncated query frame");
            }
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read query from stream", e);
        }
    }

    public void writeQuery(DataOutputStream output, String sql) {
        if (output == null) {
            throw new IllegalArgumentException("DataOutputStream cannot be null in writeQuery");
        }
        if (sql == null) {
            throw new IllegalArgumentException("SQL string cannot be null in writeQuery");
        }
        try {
            writeString(output, sql);
            output.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write query to stream", e);
        }
    }

    public ProtocolFrame readFrame(DataInputStream input) {
        if (input == null) {
            throw new IllegalArgumentException("DataInputStream cannot be null in readFrame");
        }
        try {
            int ordinal = input.readByte();
            FrameKind[] kinds = FrameKind.values();
            if (ordinal < 0 || ordinal >= kinds.length) {
                throw new IOException("Invalid FrameKind ordinal: " + ordinal);
            }
            FrameKind kind = kinds[ordinal];
            return switch (kind) {
                case SCHEMA -> {
                    String tableName = readString(input);
                    int colCount = input.readInt();
                    List<ColumnSchema> cols = new ArrayList<>(colCount);
                    for (int i = 0; i < colCount; i++) {
                        String name = readString(input);
                        DataType type = DataType.valueOf(readString(input));
                        int maxLen = input.readInt();
                        cols.add(new ColumnSchema(name, type, maxLen < 0 ? Optional.empty() : Optional.of(maxLen)));
                    }
                    Schema schema = tableName.isEmpty() ? Schema.of(cols) : Schema.of(tableName, cols);
                    yield new ProtocolFrame(FrameKind.SCHEMA, Optional.of(schema), Optional.empty(), Optional.empty());
                }
                case ROW -> {
                    int valCount = input.readInt();
                    List<Value> values = new ArrayList<>(valCount);
                    for (int i = 0; i < valCount; i++) {
                        values.add(readValue(input));
                    }
                    yield new ProtocolFrame(FrameKind.ROW, Optional.empty(), Optional.of(Row.of(values)), Optional.empty());
                }
                case SUMMARY -> {
                    boolean success = input.readBoolean();
                    long affected = input.readLong();
                    String message = readString(input);
                    boolean hasError = input.readBoolean();
                    Optional<DbmsError> error = hasError ? Optional.of(new DbmsError(readString(input))) : Optional.empty();
                    Optional<ErrorInfo> info = input.readBoolean() ? Optional.of(readErrorInfo(input)) : Optional.empty();
                    ExecutionSummary summary = new ExecutionSummary(success, affected, message, error, info);
                    yield new ProtocolFrame(FrameKind.SUMMARY, Optional.empty(), Optional.empty(), Optional.of(summary));
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read protocol frame", e);
        }
    }

    public void writeSchema(DataOutputStream output, Schema schema) {
        if (output == null || schema == null) {
            throw new IllegalArgumentException("Output and schema cannot be null in writeSchema");
        }
        try {
            output.writeByte(FrameKind.SCHEMA.ordinal());
            writeString(output, schema.tableName().orElse(""));
            List<ColumnSchema> cols = schema.getColumns();
            output.writeInt(cols.size());
            for (ColumnSchema col : cols) {
                writeString(output, col.name());
                writeString(output, col.dataType().name());
                output.writeInt(col.maxLength().orElse(-1));
            }
            output.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write schema frame", e);
        }
    }

    public void writeRow(DataOutputStream output, Row row) {
        if (output == null || row == null) {
            throw new IllegalArgumentException("Output and row cannot be null in writeRow");
        }
        try {
            output.writeByte(FrameKind.ROW.ordinal());
            List<Value> values = row.values();
            output.writeInt(values.size());
            for (Value v : values) {
                writeValue(output, v);
            }
            output.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write row frame", e);
        }
    }

    public void writeSummary(DataOutputStream output, ExecutionSummary summary) {
        if (output == null || summary == null) {
            throw new IllegalArgumentException("Output and summary cannot be null in writeSummary");
        }
        try {
            output.writeByte(FrameKind.SUMMARY.ordinal());
            output.writeBoolean(summary.isSuccess());
            output.writeLong(summary.getAffectedRows());
            writeString(output, summary.getMessage());
            Optional<DbmsError> err = summary.getError();
            output.writeBoolean(err.isPresent());
            if (err.isPresent()) {
                writeString(output, err.get().message());
            }
            Optional<ErrorInfo> info = summary.getErrorInfo();
            output.writeBoolean(info.isPresent());
            if (info.isPresent()) {
                writeErrorInfo(output, info.get());
            }
            output.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write summary frame", e);
        }
    }

    private ErrorInfo readErrorInfo(DataInputStream input) throws IOException {
        ErrorCode code = ErrorCode.valueOf(readString(input));
        ComponentType component = ComponentType.valueOf(readString(input));
        String message = readString(input);
        int position = input.readInt();
        return new ErrorInfo(code, component, message, position < 0 ? Optional.empty() : Optional.of(position));
    }

    private void writeErrorInfo(DataOutputStream output, ErrorInfo info) throws IOException {
        writeString(output, info.code().name());
        writeString(output, info.component().name());
        writeString(output, info.message());
        output.writeInt(info.position().orElse(-1));
    }

    private String readString(DataInputStream input) {
        try {
            int len = input.readInt();
            if (len <= 0) {
                return "";
            }
            byte[] bytes = input.readNBytes(len);
            if (bytes.length < len) {
                throw new EOFException("Truncated string payload");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read string", e);
        }
    }

    private void writeString(DataOutputStream output, String value) {
        try {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            output.writeInt(bytes.length);
            output.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write string", e);
        }
    }

    private Value readValue(DataInputStream input) {
        try {
            DataType type = DataType.valueOf(readString(input));
            return switch (type) {
                case INTEGER -> new Value(DataType.INTEGER, Optional.of(input.readInt()), Optional.empty(), Optional.empty());
                case FLOAT -> new Value(DataType.FLOAT, Optional.empty(), Optional.of(input.readDouble()), Optional.empty());
                case CHAR, VARCHAR -> new Value(type, Optional.empty(), Optional.empty(), Optional.of(readString(input)));
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read value", e);
        }
    }

    private void writeValue(DataOutputStream output, Value value) {
        try {
            writeString(output, value.type().name());
            switch (value.type()) {
                case INTEGER -> output.writeInt(value.integerValue().orElse(0));
                case FLOAT -> output.writeDouble(value.floatValue().orElse(0.0));
                case CHAR, VARCHAR -> writeString(output, value.textValue().orElse(""));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write value", e);
        }
    }
}

