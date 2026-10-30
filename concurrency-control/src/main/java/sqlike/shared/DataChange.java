package sqlike.shared;

/** One record change forwarded for WAL logging; the txId travels as a separate parameter. */
public record DataChange(
        ChangeOperation operation,
        String tableName,
        RID rid,
        RecordImage before,
        RecordImage after) {
}
