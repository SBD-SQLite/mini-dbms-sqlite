package sqlike.index;

import java.util.Map;
import sqlike.catalog.CatalogManager;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;

public class IndexManager {
    CatalogManager catalog;
    // RecordManager records;
    Map<String, Index> indexes;

    public IndexManager(CatalogManager catalog){
        this.catalog = catalog;
        this.indexes = indexes;
    }

    public void createIndex(IndexMetadata definition) {
        throw new UnsupportedOperationException("TODO");
    }

    public void lookup(String name, IndexCondition condition) {
        throw new UnsupportedOperationException("TODO");
    }

    public void loadIndexes() {
        throw new UnsupportedOperationException("TODO");
    }
}
