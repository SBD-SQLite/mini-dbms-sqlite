package sqlike.buffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.block.BlockManager;
import sqlike.block.Page;
import sqlike.replacement_durability.IWalDurability;
import sqlike.replacement_durability.ReplacementPolicy;
import sqlike.shared.PageId;
import sqlike.storage.StorageErrorCode;
import sqlike.storage.StorageException;

public class BufferPoolManager{
    private BlockManager blocks;
    private IWalDurability wal;
    private Map<PageId, BufferFrame> frames = new HashMap<>();
    private int capacity;
    private ReplacementPolicy replacement;

    private final Set<String> unsynced = new HashSet<>();
    private final ReentrantLock lock = new ReentrantLock();

    private long hitCount, missCount, evictionCount, flushCount;


    public BufferPoolManager(BlockManager blocks, IWalDurability wal, ReplacementPolicy replacement, int capacity) {
        this.blocks = blocks;
        this.wal = wal;
        this.replacement = replacement;
        this.capacity = capacity;
    }

    public void setIWal(IWalDurability wal){
        this.wal = wal;
    }

    public Page fetchPage(PageId id){
        lock.lock();
        try {
            BufferFrame frame = frames.get(id);
            if (frame != null) {                       // hit
                hitCount++;
                frame.pin();
                replacement.recordAccess(id);
                return frame.getPage();
            }
            missCount++;                               // miss
            makeRoom();                                // remove dulu agar pool tidak melebihi capacity
            Page page = blocks.readPage(id);           // kalo fail pool gk berubah
            install(id, page);
            return page;
        } finally {
            lock.unlock();
        }
    }

    public Page newPage(String tableId) throws StorageException {
        lock.lock();
        try {
            makeRoom();
            PageId id = blocks.allocatePage(tableId);
            Page page = new Page(id, new byte[blocks.getPageSize()]);
            install(id, page);                         // bersih: di disk juga udh nol
            return page;
        } finally {
            lock.unlock();
        }
    }

    public void unpinPage(PageId id) throws StorageException{
        lock.lock();
        try {
            BufferFrame frame = frames.get(id);
            if (frame == null || frame.getPin() == 0) {
                throw new StorageException(StorageErrorCode.INVALID_VALUE, "unpin tanpa pin: " + id);
            }
            frame.unpin();;
        } finally {
            lock.unlock();
        }
    }

    public void markDirty(PageId id, long lsn) throws StorageException{
        lock.lock();
        try {
            BufferFrame frame = frames.get(id);
            if (frame == null || frame.getPin()== 0) {
                throw new StorageException(StorageErrorCode.INVALID_VALUE,
                        "markDirty memerlukan page yang ber-pin: " + id);
            }
            if (lsn > frame.getPage().getPageLSN()) {
                frame.getPage().setPageLSN(lsn);
            }
            frame.setDirty(true);
        } finally {
            lock.unlock();
        }
    }

    public void flushPage(PageId id) throws StorageException {
        lock.lock();
        try {
            flushFrame(id, frames.get(id));
        } finally {
            lock.unlock();
        }
    }

    public void flushAll() throws StorageException {
        lock.lock();
        try {
            List<BufferFrame> dirty = new ArrayList<>();
            List<PageId> dirtyIds = new ArrayList<>();
            long maxLsn = Long.MIN_VALUE;
            for (Map.Entry<PageId, BufferFrame> e : frames.entrySet()) {
                if (e.getValue().getDirty()) {
                    dirty.add(e.getValue());
                    dirtyIds.add(e.getKey());
                    maxLsn = Math.max(maxLsn, e.getValue().getPage().getPageLSN());
                }
            }
            if (!dirty.isEmpty()) {
                wal.ensureDurable(maxLsn);
                for (int i = 0; i < dirty.size(); i++) {
                    blocks.writePage(dirty.get(i).getPage());
                    dirty.get(i).setDirty(false);
                    unsynced.add(dirtyIds.get(i).tableId());
                    flushCount++;
                }
            }
            for (String tableId : new ArrayList<>(unsynced)) {
                blocks.force(tableId);
            }
            unsynced.clear();
        } finally {
            lock.unlock();
        }
    }

    private void flushFrame(PageId id, BufferFrame frame) throws StorageException {
        if (frame == null || !frame.getDirty()) {
            return;
        }
        wal.ensureDurable(frame.getPage().getPageLSN());    // bila melempar, page TIDAK ditulis
        blocks.writePage(frame.getPage());
        frame.setDirty(false);
        unsynced.add(id.tableId());
        flushCount++;
    }


    public long getPageCount(String tableId) throws StorageException {
        return blocks.getPageCount(tableId);
    }

    public void truncateTable(String tableId) throws StorageException {
        lock.lock();
        try {
            discardFrames(tableId);
            unsynced.remove(tableId);
            blocks.truncate(tableId);
        } finally {
            lock.unlock();
        }
    }

    public void invalidateAll() throws StorageException {
        lock.lock();
        try {
            for (Map.Entry<PageId, BufferFrame> e : frames.entrySet()) {
                if (e.getValue().getPin() > 0) {
                    throw new StorageException(StorageErrorCode.INVALID_VALUE,
                            "invalidateAll: masih ada page ber-pin: " + e.getKey());
                }
            }
            frames.clear();
            replacement.clear();
        } finally {
            lock.unlock();
        }
    }

    public void close() throws StorageException {
        lock.lock();
        try {
            flushAll();
            blocks.close();
            frames.clear();
            replacement.clear();
        } finally {
            lock.unlock();
        }
    }

    public boolean isCached(PageId id) {
        lock.lock();
        try { 
            return frames.containsKey(id); 
        } finally { 
            lock.unlock(); 
        }
    }

    public int getCachedPageCount() {
        lock.lock();
        try { 
            return frames.size(); 
        } finally { 
            lock.unlock(); 
        }
    }

    public Set<PageId> getDirtyPageIds() {
        lock.lock();
        try {
            Set<PageId> result = new HashSet<>();
            for (Map.Entry<PageId, BufferFrame> e : frames.entrySet()) {
                if (e.getValue().getDirty()) result.add(e.getKey());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public long getHitCount()      { lock.lock(); try { return hitCount; }      finally { lock.unlock(); } }
    public long getMissCount()     { lock.lock(); try { return missCount; }     finally { lock.unlock(); } }
    public long getEvictionCount() { lock.lock(); try { return evictionCount; } finally { lock.unlock(); } }
    public long getFlushCount()    { lock.lock(); try { return flushCount; }    finally { lock.unlock(); } }

    private void makeRoom() throws StorageException {
        if (frames.size() >= capacity) {
            evictPage();
        }
    }

    private void install(PageId id, Page page) {
        BufferFrame frame = new BufferFrame(page);
        frames.put(id, frame);
        replacement.recordAccess(id);
    }

    private void evictPage() throws StorageException {
        PageId victim = replacement
                .chooseVictim(id -> {
                    BufferFrame f = frames.get(id);
                    return f != null && f.getPin() == 0;
                })
                .orElseThrow(() -> new StorageException(StorageErrorCode.BUFFER_FULL,
                        "buffer pool penuh dan semua page ber-pin (capacity " + capacity + ")"));
        flushFrame(victim, frames.get(victim));        // bila gagal, korban tetap di pool dan tetap kotor
        frames.remove(victim);
        replacement.remove(victim);
        evictionCount++;
    }

    private void discardFrames(String tableId) throws StorageException {
        List<PageId> toRemove = new ArrayList<>();
        for (Map.Entry<PageId, BufferFrame> e : frames.entrySet()) {
            if (e.getKey().tableId().equals(tableId)) {
                if (e.getValue().getPin() > 0) {
                    throw new StorageException(StorageErrorCode.INVALID_VALUE,
                            "tabel " + tableId + " masih punya page ber-pin: " + e.getKey());
                }
                toRemove.add(e.getKey());
            }
        }
        for (PageId id : toRemove) {
            frames.remove(id);
            replacement.remove(id);
        }
    }
}
