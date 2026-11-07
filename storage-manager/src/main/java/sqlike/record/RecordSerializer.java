package sqlike.record;

import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sqlike.shared.ColumnSchema;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import sqlike.storage.StorageException;

import static sqlike.storage.StorageErrorCode.INVALID_VALUE;
import static sqlike.storage.StorageErrorCode.IO_ERROR;

public class RecordSerializer {
    private Charset charset;
    private ByteOrder byteOrder;

    public RecordSerializer() {
        this(StandardCharsets.UTF_8, ByteOrder.BIG_ENDIAN);
    }

    public RecordSerializer(Charset charset, ByteOrder byteOrder) {
        setCharset(charset);
        setByteOrder(byteOrder);
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        if (!StandardCharsets.UTF_8.equals(charset)) {
            throw new IllegalArgumentException("RecordSerializer requires UTF-8");
        }
        this.charset = charset;
    }

    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    public void setByteOrder(ByteOrder byteOrder) {
        if (byteOrder != ByteOrder.BIG_ENDIAN) {
            throw new IllegalArgumentException("RecordSerializer requires big-endian byte order");
        }
        this.byteOrder = byteOrder;
    }

    public byte[] serialize(List<Value> values, TableSchema schema) throws StorageException {
        List<ColumnSchema> columns = snapshotColumns(schema, "serialize");
        List<Value> snapshot = snapshotValues(values, columns.size(), "serialize");
        List<byte[]> parts = new ArrayList<>(columns.size());
        int size = 0;
        for (int i = 0; i < columns.size(); i++) {
            String context = context("serialize column " + i, columns.get(i));
            byte[] part = encodeValue(snapshot.get(i), columns.get(i), context);
            size = checkedSize(size, part.length, context);
            parts.add(part);
        }
        ByteBuffer output = ByteBuffer.allocate(size).order(byteOrder);
        for (byte[] part : parts) {
            output.put(part);
        }
        return output.array();
    }

    public List<Value> deserialize(byte[] bytes, TableSchema schema) throws StorageException {
        if (bytes == null) {
            throw new StorageException(INVALID_VALUE, "deserialize: bytes must not be null");
        }
        List<ColumnSchema> columns = snapshotColumns(schema, "deserialize");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(byteOrder);
        List<Value> values = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            ColumnSchema column = columns.get(i);
            values.add(readValue(input, column, context("deserialize column " + i, column)));
        }
        if (input.hasRemaining()) {
            throw new StorageException(IO_ERROR, "deserialize: " + input.remaining()
                    + " trailing bytes at offset " + input.position());
        }
        return List.copyOf(values);
    }

    public int serializedSize(List<Value> values, TableSchema schema) throws StorageException {
        List<ColumnSchema> columns = snapshotColumns(schema, "serializedSize");
        List<Value> snapshot = snapshotValues(values, columns.size(), "serializedSize");
        int size = 0;
        for (int i = 0; i < columns.size(); i++) {
            String context = context("serializedSize column " + i, columns.get(i));
            size = checkedSize(size, encodedValueSize(snapshot.get(i), columns.get(i), context), context);
        }
        return size;
    }

    public void validate(List<Value> values, TableSchema schema) throws StorageException {
        List<ColumnSchema> columns = snapshotColumns(schema, "validate");
        List<Value> snapshot = snapshotValues(values, columns.size(), "validate");
        for (int i = 0; i < columns.size(); i++) {
            encodedValueSize(snapshot.get(i), columns.get(i), context("validate column " + i, columns.get(i)));
        }
    }

    public byte[] encodeValue(Value value, ColumnSchema column) throws StorageException {
        return encodeValue(value, column, context("encodeValue", column));
    }

    public Value decodeValue(byte[] bytes, int offset, ColumnSchema column) throws StorageException {
        String context = context("decodeValue", column);
        validateColumn(column, context);
        if (bytes == null || offset < 0 || offset > bytes.length) {
            throw new StorageException(INVALID_VALUE, context + ": invalid bytes or offset " + offset);
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(byteOrder);
        input.position(offset);
        return readValue(input, column, context);
    }

    public int encodedValueSize(Value value, ColumnSchema column) throws StorageException {
        return encodedValueSize(value, column, context("encodedValueSize", column));
    }

    private byte[] encodeValue(Value value, ColumnSchema column, String context) throws StorageException {
        validateValue(value, column, context);
        return switch (column.dataType()) {
            case INTEGER -> ByteBuffer.allocate(Integer.BYTES).order(byteOrder)
                    .putInt(value.integerValue().orElseThrow()).array();
            case FLOAT -> ByteBuffer.allocate(Double.BYTES).order(byteOrder)
                    .putDouble(value.floatValue().orElseThrow()).array();
            case CHAR, VARCHAR -> {
                ByteBuffer text = encodeText(value.textValue().orElseThrow(), context);
                int length = text.remaining();
                yield ByteBuffer.allocate(checkedSize(Integer.BYTES, length, context)).order(byteOrder)
                        .putInt(length).put(text).array();
            }
        };
    }

    private int encodedValueSize(Value value, ColumnSchema column, String context) throws StorageException {
        validateValue(value, column, context);
        return switch (column.dataType()) {
            case INTEGER -> Integer.BYTES;
            case FLOAT -> Double.BYTES;
            case CHAR, VARCHAR -> checkedSize(Integer.BYTES,
                    encodeText(value.textValue().orElseThrow(), context).remaining(), context);
        };
    }

    private static List<ColumnSchema> snapshotColumns(TableSchema schema, String operation) throws StorageException {
        if (schema == null || schema.columns() == null) {
            throw new StorageException(INVALID_VALUE, operation + ": schema and columns must not be null");
        }
        List<ColumnSchema> columns = new ArrayList<>(schema.columns());
        for (int i = 0; i < columns.size(); i++) {
            validateColumn(columns.get(i), context(operation + " column " + i, columns.get(i)));
        }
        return columns;
    }

    private static List<Value> snapshotValues(List<Value> values, int count, String operation) throws StorageException {
        if (values == null) {
            throw new StorageException(INVALID_VALUE, operation + ": values must not be null");
        }
        List<Value> snapshot = new ArrayList<>(values);
        if (snapshot.size() != count) {
            throw new StorageException(INVALID_VALUE, operation + ": expected " + count
                    + " values, got " + snapshot.size());
        }
        return snapshot;
    }

    private static void validateColumn(ColumnSchema column, String context) throws StorageException {
        if (column == null || column.dataType() == null || column.maxLength() == null) {
            throw new StorageException(INVALID_VALUE, context + ": column, type and maxLength must not be null");
        }
        switch (column.dataType()) {
            case INTEGER, FLOAT -> {
                if (column.maxLength().isPresent()) {
                    throw new StorageException(INVALID_VALUE, context + ": numeric maxLength must be empty");
                }
            }
            case CHAR, VARCHAR -> {
                if (column.maxLength().isEmpty() || column.maxLength().get() <= 0) {
                    throw new StorageException(INVALID_VALUE, context + ": text maxLength must be positive");
                }
            }
        }
    }

    private static void validateValue(Value value, ColumnSchema column, String context) throws StorageException {
        validateColumn(column, context);
        if (value == null || value.type() != column.dataType() || value.integerValue() == null
                || value.floatValue() == null || value.textValue() == null) {
            throw new StorageException(INVALID_VALUE, context + ": value type/payload containers are invalid");
        }
        boolean integer = value.integerValue().isPresent();
        boolean floating = value.floatValue().isPresent();
        boolean text = value.textValue().isPresent();
        boolean valid = switch (value.type()) {
            case INTEGER -> integer && !floating && !text;
            case FLOAT -> !integer && floating && !text;
            case CHAR, VARCHAR -> !integer && !floating && text;
        };
        if (!valid) {
            throw new StorageException(INVALID_VALUE, context + ": exactly one matching payload is required; NULL is unsupported");
        }
        if (floating && !Double.isFinite(value.floatValue().get())) {
            throw new StorageException(INVALID_VALUE, context + ": FLOAT must be finite");
        }
        if (text) {
            String string = value.textValue().get();
            if (string.codePointCount(0, string.length()) > column.maxLength().orElseThrow()) {
                throw new StorageException(INVALID_VALUE, context + ": text exceeds maxLength " + column.maxLength().get());
            }
        }
    }

    private ByteBuffer encodeText(String text, String context) throws StorageException {
        try {
            return charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new StorageException(INVALID_VALUE, context + ": invalid Unicode text", e);
        }
    }

    private Value readValue(ByteBuffer input, ColumnSchema column, String context) throws StorageException {
        context += " at offset " + input.position();
        switch (column.dataType()) {
            case INTEGER:
                requireRemaining(input, Integer.BYTES, context);
                return new Value(column.dataType(), Optional.of(input.getInt()), Optional.empty(), Optional.empty());
            case FLOAT:
                requireRemaining(input, Double.BYTES, context);
                double number = input.getDouble();
                if (!Double.isFinite(number)) {
                    throw new StorageException(IO_ERROR, context + ": stored FLOAT is not finite");
                }
                return new Value(column.dataType(), Optional.empty(), Optional.of(number), Optional.empty());
            case CHAR, VARCHAR:
                requireRemaining(input, Integer.BYTES, context);
                int length = input.getInt();
                if (length < 0) {
                    throw new StorageException(IO_ERROR, context + ": negative text byte length " + length);
                }
                requireRemaining(input, length, context);
                ByteBuffer segment = input.slice();
                segment.limit(length);
                String text;
                try {
                    text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(segment).toString();
                } catch (CharacterCodingException e) {
                    throw new StorageException(IO_ERROR, context + ": invalid UTF-8 payload", e);
                }
                if (text.codePointCount(0, text.length()) > column.maxLength().orElseThrow()) {
                    throw new StorageException(IO_ERROR, context + ": stored text exceeds maxLength " + column.maxLength().get());
                }
                input.position(input.position() + length);
                return new Value(column.dataType(), Optional.empty(), Optional.empty(), Optional.of(text));
            default:
                throw new AssertionError("Unhandled data type: " + column.dataType());
        }
    }

    private static void requireRemaining(ByteBuffer input, int length, String context) throws StorageException {
        if (length > input.remaining()) {
            throw new StorageException(IO_ERROR, context + ": need " + length
                    + " bytes, only " + input.remaining() + " remain at offset " + input.position());
        }
    }

    private static int checkedSize(int size, int addition, String context) throws StorageException {
        try {
            return Math.addExact(size, addition);
        } catch (ArithmeticException e) {
            throw new StorageException(INVALID_VALUE, context + ": encoded size exceeds Integer.MAX_VALUE", e);
        }
    }

    private static String context(String operation, ColumnSchema column) {
        return operation + (column == null ? " (null column)"
                : " '" + column.name() + "' (" + column.dataType() + ")");
    }
}
