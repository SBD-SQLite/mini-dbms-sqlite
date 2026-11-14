package sqlike.index;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Objects;
import sqlike.record.RecordSerializer;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.IndexMetadata;
import sqlike.shared.PageId;
import sqlike.shared.RID;
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

        if (numeric(column.dataType())) return Double.compare(number(left), number(right));
        String a = left.textValue().get();
        String b = right.textValue().get();
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i), y = b.codePointAt(j);
            if (x != y) return Integer.compare(x, y);
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
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

    record Entry(Value key, RID rid, byte[] encodedKey) {
        int byteSize() {
            if (encodedKey.length > Integer.MAX_VALUE - 16) throw new StorageException(INVALID_VALUE, "Index entry size overflow");
            return 16 + encodedKey.length;
        }
    }

    Entry createEntry(Value key, RID rid, String expectedTableId) throws StorageException {
        validateRid(rid, expectedTableId);
        byte[] encoded = encode(key);
        return new Entry(key, rid, encoded);
    }

    int compareEntry(Value leftKey, RID leftRid, Value rightKey, RID rightRid) throws StorageException {
        int cmp = compare(leftKey, rightKey);
        return cmp != 0 ? cmp : compareRid(leftRid, rightRid);
    }

    static int compareRid(RID left, RID right) {
        int cmp = Long.compare(left.pageId().pageNumber(), right.pageId().pageNumber());
        return cmp != 0 ? cmp : Integer.compare(left.slotId(), right.slotId());
    }

    static void validateRid(RID rid, String expectedTableId) throws StorageException {
        if (rid == null
                || rid.pageId() == null
                || rid.pageId().tableId() == null
                || rid.pageId().tableId().isBlank()
                || rid.pageId().pageNumber() < 0
                || rid.slotId() < 0
                || (expectedTableId != null && !expectedTableId.equals(rid.pageId().tableId()))) {
            throw new StorageException(INVALID_VALUE, "RID tidak valid untuk operasi indeks");
        }
    }

    static void writeEntry(ByteBuffer out, Entry entry) {
        out.putInt(entry.encodedKey().length);
        out.put(entry.encodedKey());
        out.putLong(entry.rid().pageId().pageNumber());
        out.putInt(entry.rid().slotId());
    }

    Entry readEntry(ByteBuffer in, String tableId) throws StorageException {
        if (in.remaining() < 16) {
            throw new StorageException(IO_ERROR, "Entri indeks terpotong");
        }
        int keyLen = in.getInt();
        if (keyLen < 0 || keyLen > in.remaining() - 12) {
            throw new StorageException(IO_ERROR, "Panjang key indeks tidak valid: " + keyLen);
        }
        byte[] keyBytes = new byte[keyLen];
        in.get(keyBytes);
        long pageNumber = in.getLong();
        int slotId = in.getInt();
        if (pageNumber < 0 || slotId < 0 || tableId == null || tableId.isBlank()) {
            throw new StorageException(IO_ERROR, "RID indeks pada halaman rusak");
        }
        Value key = decode(keyBytes);
        return new Entry(key, new RID(new PageId(tableId, pageNumber), slotId), keyBytes);
    }

    static String storageId(IndexMetadata metadata) throws StorageException {
        if (metadata == null
                || metadata.indexName() == null
                || metadata.indexName().isBlank()
                || !metadata.indexName().equals(metadata.indexName().strip())
                || metadata.tableName() == null
                || metadata.tableName().isBlank()
                || metadata.columnName() == null
                || metadata.columnName().isBlank()
                || metadata.indexType() == null) {
            throw new StorageException(INVALID_VALUE, "Metadata indeks tidak valid");
        }
        String raw = metadata.indexName();
        StringBuilder sb = new StringBuilder("idx_");
        HexFormat hex = HexFormat.of();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-') {
                sb.append(ch);
            } else if (ch == '_') {
                sb.append("__");
            } else {
                sb.append('_').append(hex.toHexDigits((short) ch));
            }
        }
        return sb.toString();
    }

    private static boolean numeric(DataType type) {
        return type == DataType.INTEGER || type == DataType.FLOAT;
    }

    private static double number(Value key) {
        double number = key.type() == DataType.INTEGER ? key.integerValue().get() : key.floatValue().get();
        return number == 0.0 ? 0.0 : number;
    }

    ColumnSchema column() { return column; }
}
