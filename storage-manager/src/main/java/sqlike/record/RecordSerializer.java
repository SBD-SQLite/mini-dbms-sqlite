package sqlike.record;

import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import sqlike.shared.ColumnSchema;
import sqlike.shared.TableSchema;
import sqlike.shared.Value;
import sqlike.storage.StorageException;

public class RecordSerializer {
    private Charset charset;
    private ByteOrder byteOrder;

    public RecordSerializer() {
        this(StandardCharsets.UTF_8, ByteOrder.BIG_ENDIAN);
    }

    public RecordSerializer(Charset charset, ByteOrder byteOrder) {
        this.charset = charset;
        this.byteOrder = byteOrder;
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    public void setByteOrder(ByteOrder byteOrder) {
        this.byteOrder = byteOrder;
    }

    public byte[] serialize(List<Value> values, TableSchema schema) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public List<Value> deserialize(byte[] bytes, TableSchema schema) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public int serializedSize(List<Value> values, TableSchema schema) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public void validate(List<Value> values, TableSchema schema) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public byte[] encodeValue(Value value, ColumnSchema column) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public Value decodeValue(byte[] bytes, int offset, ColumnSchema column) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public int encodedValueSize(Value value, ColumnSchema column) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    private byte[] buildNullBitmap(List<Value> values) {
        throw new UnsupportedOperationException("TODO");
    }

    private boolean isNull(byte[] bitmap, int colIndex) {
        throw new UnsupportedOperationException("TODO");
    }
}
