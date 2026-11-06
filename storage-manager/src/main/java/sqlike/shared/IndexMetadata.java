package sqlike.shared;

public class IndexMetadata {
    String indexName;
    String tableName; 
    String columnName;
    // IndexType

    public IndexMetadata(String indexName, String tableName, String columnName){
        this.indexName = indexName;
        this.tableName = tableName;
        this.columnName = columnName;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }
}
