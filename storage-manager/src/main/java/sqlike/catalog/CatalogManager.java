package sqlike.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sqlike.shared.IndexMetadata;
import sqlike.shared.TableSchema;

public class CatalogManager {
    Map<String, TableSchema> schemas;
    Map<String, IndexMetadata> indexes;
    // Path

    public CatalogManager(){
        this.schemas = new HashMap<>();
        this.indexes = new HashMap<>();
    }

    public Map<String, TableSchema> getSchemas() {
        return schemas;
    }

    public void setSchemas(Map<String, TableSchema> schemas) {
        this.schemas = schemas;
    }

    public Map<String, IndexMetadata> getIndexes() {
        return indexes;
    }

    public void setIndexes(Map<String, IndexMetadata> indexes) {
        this.indexes = indexes;
    }

    public List<String> listTables() {
        throw new UnsupportedOperationException("TODO");
    }

    public TableSchema getSchema(String table) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean tableExists(String table) {
        throw new UnsupportedOperationException("TODO");
    }

    public List<IndexMetadata> getIndexes(String table) {
        throw new UnsupportedOperationException("TODO");
    }

    public void registerIndex(IndexMetadata metadata) {
        throw new UnsupportedOperationException("TODO");
    }

    public void load() {
        throw new UnsupportedOperationException("TODO");
    }

    public void save() {
        throw new UnsupportedOperationException("TODO");
    }
}
