package sqlike.shared;

public record IndexMetadata(String indexName, String tableName, String columnName, IndexType indexType) { }
