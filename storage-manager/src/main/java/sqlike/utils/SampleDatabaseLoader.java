package sqlike.utils;

import sqlike.catalog.CatalogManager;
import sqlike.storage.StorageManager;

public class SampleDatabaseLoader {
    StorageManager storage;
    CatalogManager catalog;

    public SampleDatabaseLoader(StorageManager storage, CatalogManager catalog){
        this.storage = storage;
        this.catalog = catalog;
    }

    public StorageManager getStorage() {
        return storage;
    }

    public void setStorage(StorageManager storage) {
        this.storage = storage;
    }

    public CatalogManager getCatalog() {
        return catalog;
    }

    public void setCatalog(CatalogManager catalog) {
        this.catalog = catalog;
    }

    public void initialize() {
        throw new UnsupportedOperationException("TODO");
    }

    public void loadSampleData() {
        throw new UnsupportedOperationException("TODO");
    }
}
