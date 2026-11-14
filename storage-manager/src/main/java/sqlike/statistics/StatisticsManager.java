package sqlike.statistics;

import java.io.UncheckedIOException;
import java.util.*;
import sqlike.block.BlockManager;
import sqlike.catalog.CatalogManager;
import sqlike.record.RecordManager;
import sqlike.record.RecordSerializer;
import sqlike.shared.ColumnSchema;
import sqlike.shared.Record;
import sqlike.shared.RecordCursor;
import sqlike.shared.TableSchema;
import sqlike.shared.TableStatistics;
import sqlike.shared.Value;
import sqlike.storage.StorageException;
import static sqlike.storage.StorageErrorCode.*;

/** SM4: on-demand statistics from visible records, never placeholder values. */
public class StatisticsManager {
    @FunctionalInterface
    public interface BlockCountProvider {
        long countBlocks(String tableId) throws StorageException;
    }

    RecordManager records;
    private BlockCountProvider blockCountProvider;
    private long revision;
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

    public RecordManager getRecords() {
        return records;
    }

    public synchronized void setRecords(RecordManager records) {
        this.records = Objects.requireNonNull(records);
        cachedStats.clear();
        invalidTables.clear();
        revision++;
    }

    // hook until BlockManager exposes an allocated-page count.
    public synchronized void setBlockCountProvider(BlockCountProvider provider) {
        this.blockCountProvider = Objects.requireNonNull(provider);
        cachedStats.clear();
        invalidTables.clear();
        revision++;
    }

    public TableStatistics getStatistics(String table) throws StorageException {
        // Recompute until the CRUD owner wires every invalidation path
        return refresh(table);
    }

    public TableStatistics refresh(String table) throws StorageException {
        CatalogManager sourceCatalog;
        RecordManager sourceRecords;
        BlockCountProvider sourceCounter;
        BlockManager sourceBlocks;
        int sourcePageSize;
        long observedRevision;
        synchronized (this) {
            sourceCatalog = catalog;
            sourceRecords = records;
            sourceCounter = blockCountProvider;
            sourceBlocks = blocks;
            sourcePageSize = blocks == null ? 0 : blocks.getPageSize();
            observedRevision = revision;
        }
        if (sourceCatalog == null || sourcePageSize <= 25) {
            throw new StorageException(INVALID_STATE, "Statistics requires a catalog and valid BlockManager page size");
        }
        TableSchema schema = sourceCatalog.getSchema(table);
        String name = schema.tableName();
        if (sourceRecords == null || sourceRecords.getSerializer() == null) {
            throw new StorageException(INVALID_STATE, "Statistics requires RecordManager and its RecordSerializer");
        }
        RecordSerializer serializer = sourceRecords.getSerializer();
        long recordCount = 0;
        long totalBytes = 0;
        long observedExtent = 0;
        Map<String, Set<Object>> distinct = new LinkedHashMap<>();
        for (ColumnSchema column : schema.columns()) distinct.put(column.name(), new HashSet<>());

        // Do not hold the statistics monitor while calling record/block dependencies.
        try (RecordCursor cursor = sourceRecords.scan(name)) {
            if (cursor == null) throw new StorageException(INVALID_STATE, "RecordManager.scan returned null");
            while (cursor.hasNext()) {
                Record record = cursor.next();
                if (record == null || record.rid() == null || record.rid().pageId() == null
                        || !schema.tableId().equals(record.rid().pageId().tableId())
                        || record.rid().pageId().pageNumber() < 0 || record.rid().slotId() < 0) {
                    throw new StorageException(IO_ERROR, "Scan returned an invalid RID for " + name);
                }
                int bytes = serializer.serializedSize(record.values(), schema);
                totalBytes = Math.addExact(totalBytes, bytes);
                recordCount = Math.addExact(recordCount, 1);
                observedExtent = Math.max(observedExtent, Math.addExact(record.rid().pageId().pageNumber(), 1));
                for (int i = 0; i < schema.columns().size(); i++) {
                    distinct.get(schema.columns().get(i).name()).add(valueKey(record.values().get(i)));
                }
            }
        } catch (UncheckedIOException e) {
            failRefresh(name);
            throw new StorageException(IO_ERROR, "Cannot scan records for statistics: " + name, e.getCause());
        } catch (UnsupportedOperationException e) {
            failRefresh(name);
            throw new StorageException(INVALID_STATE, "RecordManager.scan is not implemented yet", e);
        } catch (ConcurrentModificationException e) {
            failRefresh(name);
            throw new StorageException(INVALID_STATE, "Table changed during statistics scan: " + name, e);
        } catch (StorageException e) {
            failRefresh(name);
            throw e;
        } catch (ArithmeticException e) {
            failRefresh(name);
            throw new StorageException(IO_ERROR, "Statistics counter overflow: " + name, e);
        } catch (RuntimeException e) {
            failRefresh(name);
            throw new StorageException(IO_ERROR, "Invalid scan data or cursor failure: " + name, e);
        }

        long blockCount;
        try {
            // b_r comes from the Block Manager (file size / page size)
            blockCount = sourceCounter != null ? sourceCounter.countBlocks(schema.tableId())
                    : sourceBlocks.getPageCount(schema.tableId());
        } catch (StorageException e) {
            failRefresh(name);
            throw e;
        } catch (RuntimeException e) {
            failRefresh(name);
            throw new StorageException(IO_ERROR, "Cannot count blocks: " + name, e);
        }
        if (blockCount < observedExtent) {
            failRefresh(name);
            throw new StorageException(IO_ERROR, "Block count is smaller than scanned page extent: " + name);
        }
        double average = recordCount == 0 ? 0.0 : (double) totalBytes / recordCount;
        // SlottedPage currently reserves 16 bytes/page and 9 bytes/slot; average is payload bytes only.
        int blockingFactor = recordCount == 0 ? 0 : (int) Math.floor((sourcePageSize - 16) / (average + 9));
        if (recordCount > 0 && blockingFactor < 1) {
            failRefresh(name);
            throw new StorageException(IO_ERROR, "Scanned record size exceeds configured page capacity: " + name);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        distinct.forEach((column, values) -> counts.put(column, (long) values.size()));
        TableStatistics result = new TableStatistics(recordCount, blockCount, average, blockingFactor,
                Collections.unmodifiableMap(counts));
        synchronized (this) {
            if (observedRevision != revision) {
                cachedStats.remove(name);
                invalidTables.add(name);
                throw new StorageException(INVALID_STATE, "Statistics configuration or table changed while refreshing");
            }
            cachedStats.put(name, result);
            invalidTables.remove(name);
        }
        return result;
    }

    public void invalidate(String table) throws StorageException {
        CatalogManager source;
        synchronized (this) { source = catalog; }
        String name = source.getSchema(table).tableName();
        synchronized (this) {
            cachedStats.remove(name);
            invalidTables.add(name);
            revision++;
        }
    }

    public synchronized void forget(String table) {
        cachedStats.keySet().removeIf(name -> name.equalsIgnoreCase(table));
        invalidTables.removeIf(name -> name.equalsIgnoreCase(table));
        revision++;
    }

    public synchronized void reset() {
        cachedStats.clear();
        invalidTables.clear();
        revision++;
    }

    public void recomputeAll() throws StorageException {
        CatalogManager source;
        synchronized (this) { source = catalog; }
        if (source == null) throw new StorageException(INVALID_STATE, "Statistics requires a catalog");
        StorageException first = null;
        for (String table : source.listTables()) {
            try {
                refresh(table);
            } catch (StorageException e) {
                if (first == null) first = e; else first.addSuppressed(e);
            }
        }
        if (first != null) throw first;
    }

    private synchronized void failRefresh(String table) {
        cachedStats.remove(table);
        invalidTables.add(table);
    }

    private static Object valueKey(Value value) {
        return switch (value.type()) {
            case INTEGER -> value.integerValue().orElseThrow();
            case FLOAT -> {
                double number = value.floatValue().orElseThrow();
                yield number == 0.0 ? 0.0 : number; // SQL considers +0.0 and -0.0 equal.
            }
            case CHAR, VARCHAR -> value.textValue().orElseThrow();
        };
    }
}
