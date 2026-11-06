package sqlike.shared;

public record DataChange(
        ChangeOperation operation,
        String tableName,
        RID rid,
        RecordImage before,
        RecordImage after
) {
}
