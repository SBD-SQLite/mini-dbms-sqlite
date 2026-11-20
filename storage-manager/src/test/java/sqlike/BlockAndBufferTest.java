package sqlike;

import static org.junit.jupiter.api.Assertions.*;
import static sqlike.Fixtures.codeOf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sqlike.block.BlockManager;
import sqlike.block.Page;
import sqlike.buffer.BufferPoolManager;
import sqlike.replacement_durability.LRUReplacementPolicy;
import sqlike.shared.PageId;
import sqlike.storage.StorageErrorCode;

/** Block Manager, Buffer Pool Manager and LRU replacement (SM-01 .. SM-04). */
class BlockAndBufferTest {
    @TempDir Path dir;

    @Nested
    @DisplayName("BlockManager")
    class Blocks {
        @Test void allocateWriteReadRoundTrip() {
            BlockManager bm = new BlockManager(dir, 256);
            PageId p0 = bm.allocatePage("t1");
            PageId p1 = bm.allocatePage("t1");
            assertEquals(0, p0.pageNumber());
            assertEquals(1, p1.pageNumber());
            assertEquals(2, bm.getPageCount("t1"));
            byte[] data = new byte[256];
            for (int k = 0; k < data.length; k++) data[k] = (byte) k;
            bm.writePage(new Page(p1, data));
            assertArrayEquals(data, bm.readPage(p1).getData());
            assertArrayEquals(new byte[256], bm.readPage(p0).getData());
        }

        @Test void dataSurvivesCloseAndReopen() {
            BlockManager bm = new BlockManager(dir, 128);
            PageId id = bm.allocatePage("t1");
            byte[] data = new byte[128];
            data[5] = 42;
            bm.writePage(new Page(id, data));
            bm.close();
            BlockManager again = new BlockManager(dir, 128);
            assertEquals(1, again.getPageCount("t1"));
            assertEquals(42, again.readPage(id).getData()[5]);
        }

        @Test void tableFilesUseTblAndIndexFilesUseIdx() {
            BlockManager bm = new BlockManager(dir, 128);
            bm.allocatePage("t1");
            bm.allocatePage("idx_foo");
            assertTrue(Files.exists(dir.resolve("t1.tbl")));
            assertTrue(Files.exists(dir.resolve("idx_foo.idx")));
        }

        @Test void outOfRangeAndWrongSizeAreRejected() {
            BlockManager bm = new BlockManager(dir, 128);
            bm.allocatePage("t1");
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> bm.readPage(new PageId("t1", 1))));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> bm.readPage(new PageId("t1", -1))));
            assertEquals(StorageErrorCode.INVALID_VALUE,
                    codeOf(() -> bm.writePage(new Page(new PageId("t1", 0), new byte[64]))));
        }

        @Test void invalidIdsAndPageSizeAreRejected() {
            BlockManager bm = new BlockManager(dir, 128);
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> bm.allocatePage("../evil")));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> bm.allocatePage("a b")));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> new BlockManager(dir, 0)));
        }

        @Test void truncateAndDeleteFile() {
            BlockManager bm = new BlockManager(dir, 128);
            bm.allocatePage("t1");
            bm.allocatePage("t1");
            bm.truncate("t1");
            assertEquals(0, bm.getPageCount("t1"));
            bm.allocatePage("t1");
            bm.deleteFile("t1");
            assertFalse(Files.exists(dir.resolve("t1.tbl")));
            assertEquals(0, bm.getPageCount("t1"));
        }

        @Test void corruptedFileSizeIsDetected() throws Exception {
            Files.write(dir.resolve("bad.tbl"), new byte[100]);
            BlockManager bm = new BlockManager(dir, 128);
            assertEquals(StorageErrorCode.CORRUPTED_DATA, codeOf(() -> bm.getPageCount("bad")));
        }

        @Test void closeIsIdempotent() {
            BlockManager bm = new BlockManager(dir, 128);
            bm.allocatePage("t1");
            bm.close();
            assertDoesNotThrow(bm::close);
        }
    }

    @Nested
    @DisplayName("BufferPoolManager + LRU")
    class Buffer {
        final Fixtures.RecordingWal wal = new Fixtures.RecordingWal();

        BufferPoolManager pool(int capacity) {
            return new BufferPoolManager(new BlockManager(dir, 128), wal, new LRUReplacementPolicy(), capacity);
        }

        @Test void newPageIsPinnedAndHitCountsAreTracked() {
            BufferPoolManager bp = pool(4);
            Page p = bp.newPage("t1");
            bp.unpinPage(p.getPageId());
            bp.fetchPage(p.getPageId());
            bp.unpinPage(p.getPageId());
            assertEquals(1, bp.getHitCount());
            assertTrue(bp.isCached(p.getPageId()));
        }

        @Test void evictsLeastRecentlyUsedUnpinnedPage() {
            BufferPoolManager bp = pool(2);
            Page a = bp.newPage("t1"); bp.unpinPage(a.getPageId());
            Page b = bp.newPage("t1"); bp.unpinPage(b.getPageId());
            bp.fetchPage(a.getPageId()); bp.unpinPage(a.getPageId()); // a is now most recent
            Page c = bp.newPage("t1"); bp.unpinPage(c.getPageId());   // must evict b
            assertTrue(bp.isCached(a.getPageId()));
            assertFalse(bp.isCached(b.getPageId()));
            assertTrue(bp.isCached(c.getPageId()));
            assertEquals(1, bp.getEvictionCount());
            assertEquals(2, bp.getCachedPageCount());
        }

        @Test void pinnedPagesAreNeverEvictedAndFullPoolFailsWithBufferFull() {
            BufferPoolManager bp = pool(2);
            bp.newPage("t1");
            bp.newPage("t1"); // both still pinned
            assertEquals(StorageErrorCode.BUFFER_FULL, codeOf(() -> bp.newPage("t1")));
        }

        @Test void unpinWithoutPinAndMarkDirtyWithoutPinAreRejected() {
            BufferPoolManager bp = pool(2);
            Page p = bp.newPage("t1");
            bp.unpinPage(p.getPageId());
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> bp.unpinPage(p.getPageId())));
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> bp.markDirty(p.getPageId(), 5)));
        }

        @Test void walIsForcedUpToPageLsnBeforeTheDirtyPageIsWritten() {
            BufferPoolManager bp = pool(2);
            Page p = bp.newPage("t1");
            p.getData()[0] = 0; // header LSN lives in the first 8 bytes; we use markDirty's LSN
            bp.markDirty(p.getPageId(), 77);
            bp.unpinPage(p.getPageId());
            bp.flushPage(p.getPageId());
            assertEquals(77, wal.max());
            assertTrue(bp.getDirtyPageIds().isEmpty());
            assertEquals(1, bp.getFlushCount());
        }

        @Test void walFailureLeavesPageDirtyAndUnwritten() {
            BufferPoolManager bp = pool(2);
            Page p = bp.newPage("t1");
            p.getData()[10] = 9;
            bp.markDirty(p.getPageId(), 5);
            bp.unpinPage(p.getPageId());
            wal.reject = true;
            assertEquals(StorageErrorCode.WAL_FAILURE, codeOf(() -> bp.flushAll()));
            assertTrue(bp.getDirtyPageIds().contains(p.getPageId()));
            wal.reject = false;
            assertDoesNotThrow(bp::flushAll);
            assertTrue(bp.getDirtyPageIds().isEmpty());
        }

        @Test void dirtyVictimIsFlushedOnEvictionAndReadBack() {
            BufferPoolManager bp = pool(1);
            Page a = bp.newPage("t1");
            a.getData()[20] = 7;
            bp.markDirty(a.getPageId(), 3);
            bp.unpinPage(a.getPageId());
            Page b = bp.newPage("t1"); // evicts a (dirty) -> WAL then write
            bp.unpinPage(b.getPageId());
            assertTrue(wal.requested.contains(3L));
            assertEquals(7, bp.fetchPage(a.getPageId()).getData()[20]);
        }

        @Test void evictionWithWalFailureKeepsVictimCached() {
            BufferPoolManager bp = pool(1);
            Page a = bp.newPage("t1");
            bp.markDirty(a.getPageId(), 3);
            bp.unpinPage(a.getPageId());
            wal.reject = true;
            assertEquals(StorageErrorCode.WAL_FAILURE, codeOf(() -> bp.newPage("t1")));
            assertTrue(bp.isCached(a.getPageId()));
        }

        @Test void truncateTableDropsFramesButRefusesPinnedOnes() {
            BufferPoolManager bp = pool(4);
            Page a = bp.newPage("t1");
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> bp.truncateTable("t1")));
            bp.unpinPage(a.getPageId());
            bp.truncateTable("t1");
            assertFalse(bp.isCached(a.getPageId()));
            assertEquals(0, bp.getPageCount("t1"));
        }

        @Test void invalidateAllDiscardsWithoutFlush() {
            BufferPoolManager bp = pool(4);
            Page a = bp.newPage("t1");
            bp.markDirty(a.getPageId(), 4);
            assertEquals(StorageErrorCode.INVALID_VALUE, codeOf(() -> bp.invalidateAll())); // pinned
            bp.unpinPage(a.getPageId());
            bp.invalidateAll();
            assertEquals(0, bp.getCachedPageCount());
            assertTrue(wal.requested.isEmpty(), "invalidateAll must not flush");
        }

        @Test void closeFlushesEverythingToDisk() {
            BlockManager bm = new BlockManager(dir, 128);
            BufferPoolManager bp = new BufferPoolManager(bm, wal, new LRUReplacementPolicy(), 4);
            Page a = bp.newPage("persist");
            a.getData()[30] = 11;
            bp.markDirty(a.getPageId(), 9);
            bp.unpinPage(a.getPageId());
            bp.close();
            assertEquals(11, new BlockManager(dir, 128).readPage(a.getPageId()).getData()[30]);
        }
    }

    @Test void lruChooseVictimHonoursEvictablePredicateAndEmptyPolicy() {
        LRUReplacementPolicy lru = new LRUReplacementPolicy();
        PageId a = new PageId("t", 0), b = new PageId("t", 1), c = new PageId("t", 2);
        assertEquals(Optional.empty(), lru.chooseVictim(id -> true));
        lru.recordAccess(a); lru.recordAccess(b); lru.recordAccess(c);
        assertEquals(Optional.of(a), lru.chooseVictim(id -> true));
        assertEquals(Optional.of(b), lru.chooseVictim(id -> !id.equals(a)));
        lru.recordAccess(a);
        assertEquals(Optional.of(b), lru.chooseVictim(id -> true));
        lru.remove(b);
        assertEquals(Optional.of(c), lru.chooseVictim(id -> true));
        lru.clear();
        assertEquals(Optional.empty(), lru.chooseVictim(id -> true));
    }
}
