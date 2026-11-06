package sqlike.shared;

import java.util.ArrayList;
import java.util.List;

public class TableSchema {
    String tableId;
    String tableName;
    List<ColumnSchema> columns;

    public TableSchema(String tableId, String tableName, List<ColumnSchema> columns){
        this.tableId = tableId;
        this.tableName = tableName;
        this.columns = new ArrayList<>(columns);
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<ColumnSchema> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnSchema> columns) {
        this.columns = columns;
    }
}
