package sqlike.index;

import java.util.Objects;
import sqlike.record.RecordSerializer;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.Value;
import sqlike.storage.StorageException;

import static sqlike.storage.StorageErrorCode.INVALID_VALUE;
import static sqlike.storage.StorageErrorCode.IO_ERROR;

public class IndexHelper {
    private final ColumnSchema column;
    private final RecordSerializer serializer;

    public IndexHelper(ColumnSchema column, RecordSerializer serializer) {

        this.column = Objects.requireNonNull(column, "column");

        this.serializer = Objects.requireNonNull(serializer, "serializer");

        if (column.dataType() == null || column.maxLength() == null || (numeric(column.dataType()) ? column.maxLength().isPresent() : column.maxLength().isEmpty() || column.maxLength().get() <= 0)) {
            throw new IllegalArgumentException("Index column mempunyai invalid tipe atau maxLength");
        }
    }

    public void validateStoredKey(Value key) throws StorageException {
        serializer.encodedValueSize(key, column);
    }

    public void validateLookupKey(Value key) throws StorageException {
        if (key == null || key.type() == null || key.integerValue() == null || key.floatValue() == null || key.textValue() == null || numeric(key.type()) != numeric(column.dataType())) {
            throw new StorageException(INVALID_VALUE, "Key mempunyai invalid tipe atau isi");
        }

        boolean valid = switch (key.type()) {
            case INTEGER -> key.integerValue().isPresent() && key.floatValue().isEmpty() && key.textValue().isEmpty();
            case FLOAT -> key.integerValue().isEmpty() && key.floatValue().isPresent() && key.textValue().isEmpty() && Double.isFinite(key.floatValue().get());
            case CHAR, VARCHAR -> key.integerValue().isEmpty() && key.floatValue().isEmpty() && key.textValue().isPresent();
        };

        if (!valid) {
            throw new StorageException(INVALID_VALUE, "Key harus punya satu pasangan");
        }

        if (key.textValue().isPresent()) {
            String text = key.textValue().get();
        IndexHelper keys = new IndexHelper(schema.columns().get(column), serializer);

            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (Character.isSurrogate(ch)) {
                    if (!Character.isHighSurrogate(ch) || i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                        throw new StorageException(INVALID_VALUE, "Unicode invalid");
                    }
                    i++;
                }
            }
        }
    }

    public int compare(Value left, Value right) throws StorageException {
        validateLookupKey(left);
        validateLookupKey(right);

        return numeric(column.dataType()) ? Double.compare(number(left), number(right)) : left.textValue().get().compareTo(right.textValue().get());
    }

    public int hash(Value key) throws StorageException {
        validateLookupKey(key);

        return numeric(column.dataType()) ? Double.hashCode(number(key)) : key.textValue().get().hashCode();
    }

    public byte[] encode(Value key) throws StorageException {
        return serializer.encodeValue(key, column);
    }

    public Value decode(byte[] bytes) throws StorageException {
        Value key = serializer.decodeValue(bytes, 0, column);
        if (serializer.encodedValueSize(key, column) != bytes.length) {
            throw new StorageException(IO_ERROR, "Index key mempunyai trailing bytes");
        }
        
        return key;
    }

    private static boolean numeric(DataType type) {
        return type == DataType.INTEGER || type == DataType.FLOAT;
    }

    private static double number(Value key) {
        return key.type() == DataType.INTEGER ? key.integerValue().get() : key.floatValue().get();
    }
}
