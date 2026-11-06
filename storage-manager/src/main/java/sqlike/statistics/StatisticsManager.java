package sqlike.statistics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import sqlike.block.BlockManager;
import sqlike.catalog.CatalogManager;
import sqlike.shared.TableStatistics;

public class StatisticsManager {
    // RecordManager
    CatalogManager catalog;
    BlockManager blocks;
    Map<String, TableStatistics> cachedStats;
    Set<String> invalidTables;

    public StatisticsManager(CatalogManager catalog, BlockManager blocks){
        this.catalog = catalog;
        this.blocks = blocks;
        this.cachedStats = new HashMap<>();
        this.invalidTables = new HashSet<>();
    }

    public CatalogManager getCatalog() {
        return catalog;
    }

    public void setCatalog(CatalogManager catalog) {
        this.catalog = catalog;
    }

    public BlockManager getBlocks() {
        return blocks;
    }

    public void setBlocks(BlockManager blocks) {
        this.blocks = blocks;
    }

    public Map<String, TableStatistics> getCachedStats() {
        return cachedStats;
    }

    public void setCachedStats(Map<String, TableStatistics> cachedStats) {
        this.cachedStats = cachedStats;
    }

    public Set<String> getInvalidTables() {
        return invalidTables;
    }

    public void setInvalidTables(Set<String> invalidTables) {
        this.invalidTables = invalidTables;
    }

    public TableStatistics getStatistics(String table) {
        throw new UnsupportedOperationException("TODO");
    }

    public TableStatistics refresh(String table) {
        throw new UnsupportedOperationException("TODO");
    }

    public void invalidate(String table) {
        throw new UnsupportedOperationException("TODO");
    }
}
