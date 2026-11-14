package sqlike.index;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import sqlike.block.Page;
import sqlike.buffer.BufferPoolManager;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexStatistics;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.Value;
import static sqlike.storage.StorageErrorCode.INVALID_STATE;
import static sqlike.storage.StorageErrorCode.INVALID_VALUE;
import static sqlike.storage.StorageErrorCode.IO_ERROR;
import sqlike.storage.StorageException;

public class HashIndex implements Index {
    // [Utama] Atribut sesuai diagram
    private final BufferPoolManager buffer;
    private final IndexMetadata indexMetadata;
    private PageId directoryPageId;
    private int bucketCount;

    // [Helper] Format page dan dukungan key/RID
    private static final int HEADER = 32;
    private static final int DIRECTORY = 1, BUCKET = 2;
    private static final int INDEX_PAGE_SIGNATURE = 0x534C4958;
    private final IndexHelper keys;
    private final String storageId;
    private final String tableId;
    private int pageSize;
    private byte[] identity;
    private boolean invalid;

    public HashIndex(BufferPoolManager buffer, IndexMetadata metadata, IndexHelper keys, String tableId) {
        this.buffer = Objects.requireNonNull(buffer);
        this.indexMetadata = Objects.requireNonNull(metadata);
        this.keys = keys;
        this.storageId = IndexHelper.storageId(indexMetadata);
        this.tableId = Objects.requireNonNull(tableId);
    }

    // Method - Index
    @Override
    public synchronized List<RID> search(Value key) {
        keys.validateLookupKey(key);
        readDescriptor();
        PageId first = getBucketPage(hashKey(key));
        long page = first == null ? -1 : first.pageNumber();
        Set<Long> visited = new HashSet<>();
        List<RID> matches = new ArrayList<>();
        while (page != -1) {
            if (!visited.add(page)) throw new StorageException(IO_ERROR, "Hash bucket cycle");
            Bucket bucket = readBucket(page);
            for (IndexHelper.Entry entry : bucket.entries) {
                if (keys.compare(entry.key(), key) == 0) matches.add(entry.rid());
            }
            page = bucket.next;
        }
        return List.copyOf(matches);
    }

    @Override
    public synchronized void insert(Value key, RID rid, long lsn) {
        IndexHelper.Entry entry = keys.createEntry(key, rid, tableId);
        if (lsn <= 0) throw new StorageException(INVALID_VALUE, "Invalid insertion LSN");
        readDescriptor();
        if (entry.byteSize() > pageSize - HEADER) throw new StorageException(INVALID_VALUE, "Oversized hash entry");
        int slot = hashKey(key);
        PageId first = getBucketPage(slot);
        long current = first == null ? -1 : first.pageNumber();
        Bucket available = null, last = null;
        Set<Long> visited = new HashSet<>();
        while (current != -1) {
            if (!visited.add(current)) throw new StorageException(IO_ERROR, "Hash bucket cycle");
            Bucket bucket = readBucket(current);
            for (IndexHelper.Entry old : bucket.entries) {
                if (keys.compareEntry(old.key(), old.rid(), key, rid) == 0) return;
            }
            if (available == null && byteSize(bucket) + entry.byteSize() <= pageSize) available = bucket;
            last = bucket;
            current = bucket.next;
        }
        Map<Long, ByteBuffer> changes = new LinkedHashMap<>();
        if (available == null) {
            PageId allocated = allocateOverflowPage(slot, last, changes);
            available = new Bucket(allocated.pageNumber(), -1, new ArrayList<>());
        }
        available.entries.add(entry);
        changes.put(available.id, bucketImage(available));
        writePages(changes, lsn);
    }

    @Override
    public synchronized void remove(Value key, RID rid, long lsn) {
        keys.validateStoredKey(key);
        IndexHelper.validateRid(rid, tableId);
        if (lsn <= 0) throw new StorageException(INVALID_VALUE, "Invalid removal LSN");
        readDescriptor();
        PageId first = getBucketPage(hashKey(key));
        long current = first == null ? -1 : first.pageNumber();
        Set<Long> visited = new HashSet<>();
        while (current != -1) {
            if (!visited.add(current)) throw new StorageException(IO_ERROR, "Hash bucket cycle");
            Bucket bucket = readBucket(current);
            for (int i = 0; i < bucket.entries.size(); i++) {
                IndexHelper.Entry entry = bucket.entries.get(i);
                if (keys.compareEntry(entry.key(), entry.rid(), key, rid) == 0) {
                    bucket.entries.remove(i);
                    writePages(Map.of(bucket.id, bucketImage(bucket)), lsn);
                    return;
                }
            }
            current = bucket.next;
        }
    }

    @Override
    public synchronized void flush() {
        if (invalid) throw new StorageException(INVALID_STATE, "Index requires rebuild after failed rollback");
        buffer.flushAll();
    }

    @Override
    public synchronized IndexStatistics getStatistics() {
        readDescriptor();
        ByteBuffer directory = readPage(directoryPageId.pageNumber(), DIRECTORY);
        if (directory.getInt(20) != bucketCount) throw new StorageException(IO_ERROR, "Directory count mismatch");

        long overflowPages = 0;
        Set<Long> visited = new HashSet<>();
        for (int i = 0; i < bucketCount; i++) {
            long page = directory.getLong();
            checkPointer(page);
            boolean first = true;
            while (page != -1) {
                if (!visited.add(page)) throw new StorageException(IO_ERROR, "Hash bucket cycle");
                if (!first) overflowPages++;
                Bucket bucket = readBucket(page);
                page = bucket.next;
                first = false;
            }
        }
        return new IndexStatistics(OptionalInt.empty(), OptionalInt.of(bucketCount),
                OptionalLong.empty(), OptionalLong.of(overflowPages), false);
    }

    // [Helper] Inisialisasi, bucket, dan penyimpanan page
    synchronized void initialize(long lsn) {
        if (invalid) throw new StorageException(INVALID_STATE, "Index requires cleanup before rebuild");
        if (lsn <= 0) throw new StorageException(INVALID_VALUE, "Invalid build LSN");
        if (buffer.getPageCount(storageId) != 0) throw new StorageException(INVALID_STATE, "Hash index already exists");
        try {
            if (allocatePage() != 0) throw new StorageException(INVALID_STATE, "Invalid descriptor allocation");
            long directory = allocatePage();
            int buckets = Math.min(64, (pageSize - HEADER) / Long.BYTES);
            ByteBuffer out = pageImage(DIRECTORY, buckets, -1);
            for (int i = 0; i < buckets; i++) out.putLong(-1);
            ByteBuffer descriptor = pageImage(0, 0, -1);
            descriptor.putInt(identity.length).put(identity).putLong(directory).putInt(buckets);
            Map<Long, ByteBuffer> changes = new LinkedHashMap<>();
            changes.put(directory, out);
            changes.put(0L, descriptor);
            writePages(changes, lsn);
            directoryPageId = new PageId(storageId, directory);
            bucketCount = buckets;
        } catch (RuntimeException failure) {
            try { discard(); }
            catch (RuntimeException cleanup) { invalid = true; failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    synchronized void discard() {
        buffer.truncateTable(storageId);
        directoryPageId = null;
        bucketCount = 0;
        pageSize = 0;
        identity = null;
        invalid = false;
    }

    private void configure(int size) {
        if (pageSize != 0) {
            if (pageSize != size) throw new StorageException(IO_ERROR, "Index page size changed");
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String field : new String[]{indexMetadata.indexName(), indexMetadata.tableName(), indexMetadata.columnName(),
                    indexMetadata.indexType().name(), tableId, keys.column().dataType().name()}) out.writeUTF(field);
            out.writeInt(keys.column().maxLength().orElse(0));
            out.writeInt(size);
            identity = bytes.toByteArray();
        } catch (IOException e) {
            throw new StorageException(INVALID_VALUE, "Index identity is too large", e);
        }
        if ((long) HEADER + Integer.BYTES + identity.length + Long.BYTES + Integer.BYTES > size) {
            throw new StorageException(INVALID_VALUE, "Index descriptor does not fit page size " + size);
        }
        pageSize = size;
    }

    private int hashKey(Value key) {
        return Math.floorMod(keys.hash(key), bucketCount);
    }

    // Pointer -1 di disk berarti bucket belum dialokasikan dan hasilnya null
    private PageId getBucketPage(int bucketId) {
        if (bucketId < 0 || bucketId >= bucketCount) throw new StorageException(INVALID_VALUE, "Invalid bucket ID");
        ByteBuffer in = readPage(directoryPageId.pageNumber(), DIRECTORY);
        if (in.getInt(20) != bucketCount) throw new StorageException(IO_ERROR, "Directory count mismatch");
        long selected = -1;
        for (int i = 0; i < bucketCount; i++) {
            long pointer = in.getLong();
            checkPointer(pointer);
            if (i == bucketId) selected = pointer;
        }
        return selected == -1 ? null : new PageId(storageId, selected);
    }

    // Bucket terakhir diteruskan agar chain tidak perlu dipindai ulang
    private PageId allocateOverflowPage(int bucketId, Bucket last, Map<Long, ByteBuffer> changes) {
        long number = allocatePage();
        changes.put(number, pageImage(BUCKET, 0, -1));
        if (last == null) {
            ByteBuffer directory = readPage(directoryPageId.pageNumber(), DIRECTORY);
            directory.putLong(HEADER + bucketId * Long.BYTES, number);
            changes.put(directoryPageId.pageNumber(), directory);
        } else {
            last.next = number;
            changes.put(last.id, bucketImage(last));
        }
        return new PageId(storageId, number);
    }

    private Bucket readBucket(long id) {
        ByteBuffer in = readPage(id, BUCKET);
        int count = in.getInt(20);
        long next = in.getLong(24);
        checkPointer(next);
        if (count < 0 || count > in.remaining() / 16) throw new StorageException(IO_ERROR, "Invalid hash entry count");
        List<IndexHelper.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(keys.readEntry(in, tableId));
        return new Bucket(id, next, entries);
    }

    private int byteSize(Bucket bucket) {
        return HEADER + bucket.entries.stream().mapToInt(IndexHelper.Entry::byteSize).sum();
    }

    private ByteBuffer bucketImage(Bucket bucket) {
        ByteBuffer out = pageImage(BUCKET, bucket.entries.size(), bucket.next);
        for (IndexHelper.Entry entry : bucket.entries) IndexHelper.writeEntry(out, entry);
        return out;
    }

    private long allocatePage() {
        Page page = buffer.newPage(storageId);
        try {
            configure(page.getData().length);
            return page.getPageId().pageNumber();
        }
        finally { buffer.unpinPage(page.getPageId()); }
    }

    private ByteBuffer readPage(long number, int type) {
        if (number < 0 || number >= buffer.getPageCount(storageId)) throw new StorageException(IO_ERROR, "Invalid index page pointer " + number);
        PageId id = new PageId(storageId, number);
        Page page = buffer.fetchPage(id);
        try {
            configure(page.getData().length);
            ByteBuffer in = ByteBuffer.wrap(page.getData().clone()).order(ByteOrder.BIG_ENDIAN);
            if (in.getLong(0) < 0 || in.getInt(8) != INDEX_PAGE_SIGNATURE || in.getInt(12) != 1 || in.getInt(16) != type) {
                throw new StorageException(IO_ERROR, "Invalid index page header: " + id);
            }
            in.position(HEADER);
            return in;
        } finally { buffer.unpinPage(id); }
    }

    private ByteBuffer pageImage(int type, int count, long link) {
        ByteBuffer out = ByteBuffer.allocate(pageSize).order(ByteOrder.BIG_ENDIAN);
        out.putLong(0).putInt(INDEX_PAGE_SIGNATURE).putInt(1).putInt(type).putInt(count).putLong(link);
        return out;
    }

    private void writePages(Map<Long, ByteBuffer> changes, long lsn) {
        Map<Long, byte[]> before = new LinkedHashMap<>();
        for (long number : changes.keySet()) {
            PageId id = new PageId(storageId, number);
            Page page = buffer.fetchPage(id);
            try { before.put(number, page.getData().clone()); }
            finally { buffer.unpinPage(id); }
        }
        List<Long> attempted = new ArrayList<>();
        try {
            for (Map.Entry<Long, ByteBuffer> change : changes.entrySet()) {
                attempted.add(change.getKey());
                writePage(change.getKey(), change.getValue(), lsn);
                buffer.flushPage(new PageId(storageId, change.getKey()));
            }
            buffer.flushAll();
        } catch (RuntimeException failure) {
            try {
                for (int i = attempted.size() - 1; i >= 0; i--) {
                    long number = attempted.get(i);
                    writePage(number, ByteBuffer.wrap(before.get(number)), lsn);
                    buffer.flushPage(new PageId(storageId, number));
                }
                buffer.flushAll();
            } catch (RuntimeException rollback) {
                invalid = true;
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    private void writePage(long number, ByteBuffer image, long lsn) {
        if (lsn <= 0) throw new StorageException(INVALID_VALUE, "Index mutation needs positive LSN");
        PageId id = new PageId(storageId, number);
        Page page = buffer.fetchPage(id);
        try {
            long old = ByteBuffer.wrap(page.getData()).getLong(0);
            image.putLong(0, Math.max(old, lsn));
            System.arraycopy(image.array(), 0, page.getData(), 0, pageSize);
            page.setPageLSN(Math.max(old, lsn));
            buffer.markDirty(id, lsn);
        } finally { buffer.unpinPage(id); }
    }

    private void readDescriptor() {
        if (invalid) throw new StorageException(INVALID_STATE, "Index requires rebuild after failed rollback");
        ByteBuffer in = readPage(0, 0);
        int length = in.getInt();
        if (length != identity.length || length > in.remaining() - 12) throw new StorageException(IO_ERROR, "Invalid index descriptor identity length");
        byte[] found = new byte[length];
        in.get(found);
        if (!Arrays.equals(identity, found)) throw new StorageException(IO_ERROR, "Index descriptor/schema mismatch");
        long root = in.getLong();
        int config = in.getInt();
        if (root <= 0 || root >= buffer.getPageCount(storageId) || config <= 0) throw new StorageException(IO_ERROR, "Invalid index root/configuration");
        if (config > (pageSize - HEADER) / Long.BYTES) throw new StorageException(IO_ERROR, "Invalid bucket count");
        directoryPageId = new PageId(storageId, root);
        bucketCount = config;
    }

    private void checkPointer(long number) {
        if (number != -1 && (number <= 0 || number >= buffer.getPageCount(storageId))) throw new StorageException(IO_ERROR, "Invalid index link " + number);
    }

    private static final class Bucket {
        final long id;
        long next;
        final List<IndexHelper.Entry> entries;
        Bucket(long id, long next, List<IndexHelper.Entry> entries) {
            this.id = id;
            this.next = next;
            this.entries = entries;
        }
    }
}
