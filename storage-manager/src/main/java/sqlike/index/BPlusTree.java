package sqlike.index;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import sqlike.block.Page;
import sqlike.buffer.BufferPoolManager;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.IndexOperator;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.Value;
import sqlike.storage.StorageException;
import static sqlike.storage.StorageErrorCode.*;

public class BPlusTree implements Index {
    // [Utama] Atribut sesuai diagram
    private PageId rootPageId;
    private int order;
    private final BufferPoolManager buffer;
    private final IndexMetadata indexMetadata;

    // [Helper] Format page dan dukungan key/RID
    private static final int HEADER = 32;
    private static final int LEAF = 3, BRANCH = 4;
    private static final int INDEX_PAGE_SIGNATURE = 0x534C4958;
    private final IndexHelper keys;
    private final String storageId;
    private final String tableId;
    private int pageSize;
    private byte[] identity;
    private int maxEntrySize;
    private boolean invalid;
    private final Map<Long, ByteBuffer> changes = new LinkedHashMap<>();

    public BPlusTree(BufferPoolManager buffer, IndexMetadata metadata, IndexHelper keys, String tableId) {
        this.buffer = Objects.requireNonNull(buffer);
        this.indexMetadata = Objects.requireNonNull(metadata);
        this.keys = keys;
        this.storageId = IndexHelper.storageId(indexMetadata);
        this.tableId = Objects.requireNonNull(tableId);
    }

    // Method - Index
    @Override
    public List<RID> search(Value key) {
        return searchRange(new IndexCondition(IndexOperator.EQ, key, java.util.Optional.empty(), true, true));
    }

    public synchronized List<RID> searchRange(IndexCondition condition) {
        if (condition == null || condition.operator() == null || condition.upperBound() == null) {
            throw new StorageException(INVALID_VALUE, "Invalid B+Tree condition");
        }
        keys.validateLookupKey(condition.key());
        Value upper = condition.operator() == IndexOperator.BETWEEN
                ? condition.upperBound().orElseThrow(() -> new StorageException(INVALID_VALUE, "Missing upper bound")) : condition.key();
        if (condition.operator() == IndexOperator.BETWEEN && keys.compare(condition.key(), upper) > 0) {
            throw new StorageException(INVALID_VALUE, "Reversed range bounds");
        }
        boolean fromLeft = condition.operator() == IndexOperator.LT || condition.operator() == IndexOperator.LE;
        readDescriptor();
        Node node = findLeaf(condition.key(), fromLeft);
        Set<Long> visited = new HashSet<>();
        List<RID> result = new ArrayList<>();
        while (true) {
            if (!visited.add(node.id)) throw new StorageException(IO_ERROR, "B+Tree leaf cycle");
            for (IndexHelper.Entry entry : node.entries) {
                int low = keys.compare(entry.key(), condition.key());
                int high = condition.operator() == IndexOperator.BETWEEN ? keys.compare(entry.key(), upper) : low;
                boolean match = switch (condition.operator()) {
                    case EQ -> low == 0;
                    case GT -> low > 0;
                    case GE -> low >= 0;
                    case LT -> low < 0;
                    case LE -> low <= 0;
                    case BETWEEN -> (low > 0 || low == 0 && condition.lowerInclusive())
                            && (high < 0 || high == 0 && condition.upperInclusive());
                };
                boolean done = switch (condition.operator()) {
                    case EQ, LE -> low > 0;
                    case LT -> low >= 0;
                    case BETWEEN -> high > 0 || high == 0 && !condition.upperInclusive();
                    default -> false;
                };
                if (done) return List.copyOf(result);
                if (match) result.add(entry.rid());
            }
            if (node.next == -1) return List.copyOf(result);
            node = readNode(node.next);
            if (!node.leaf) throw new StorageException(IO_ERROR, "Leaf link points to an internal node");
        }
    }


    @Override
    public synchronized void insert(Value key, RID rid, long lsn) {
        change(key, rid, lsn, true);
    }

    @Override
    public synchronized void remove(Value key, RID rid, long lsn) {
        change(key, rid, lsn, false);
    }

    @Override
    public synchronized void flush() {
        if (invalid) throw new StorageException(INVALID_STATE, "Index requires rebuild after failed rollback");
        buffer.flushAll();
    }

    // [Helper] Inisialisasi, mutasi node, dan penyimpanan page.
    synchronized void initialize(long lsn) {
        if (invalid) throw new StorageException(INVALID_STATE, "Index requires cleanup before rebuild");
        if (lsn <= 0) throw new StorageException(INVALID_VALUE, "Invalid build LSN");
        if (buffer.getPageCount(storageId) != 0) throw new StorageException(INVALID_STATE, "B+Tree already exists");
        try {
            if (allocatePage() != 0) throw new StorageException(INVALID_STATE, "Invalid descriptor allocation");
            Node root = new Node(allocatePage(), true);
            writeNode(root);
            writeDescriptor(root.id);
            commitChanges(lsn);
        } catch (RuntimeException failure) {
            try { discard(); }
            catch (RuntimeException cleanup) { invalid = true; failure.addSuppressed(cleanup); }
            throw failure;
        } finally { changes.clear(); }
    }

    synchronized void discard() {
        buffer.truncateTable(storageId);
        changes.clear();
        rootPageId = null;
        pageSize = order = maxEntrySize = 0;
        identity = null;
        invalid = false;
    }

    private void configure(int size) {
        if (pageSize != 0) {
            if (pageSize != size) throw new StorageException(IO_ERROR, "Index page size changed");
            return;
        }
        pageSize = size;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String field : new String[]{indexMetadata.indexName(), indexMetadata.tableName(), indexMetadata.columnName(),
                    indexMetadata.indexType().name(), tableId, keys.column().dataType().name()}) out.writeUTF(field);
            out.writeInt(keys.column().maxLength().orElse(0));
            out.writeInt(pageSize);
            identity = bytes.toByteArray();
        } catch (IOException e) {
            throw new StorageException(INVALID_VALUE, "Index identity is too large", e);
        }
        if ((long) HEADER + Integer.BYTES + identity.length + Long.BYTES + Integer.BYTES > pageSize) {
            throw new StorageException(INVALID_VALUE, "Index descriptor does not fit page size " + pageSize);
        }
        order = (pageSize - HEADER - 8) / 28 + 1;
        // Tiga separator dan empat pointer harus muat agar internal node dapat dipecah
        maxEntrySize = (pageSize - HEADER - 32) / 3;
        if (maxEntrySize < 20) throw new StorageException(INVALID_VALUE, "B+Tree page size is too small");
    }

    private Node findLeaf(Value key, boolean fromLeft) {
        Node node = readNode(rootPageId.pageNumber());
        Set<Long> visited = new HashSet<>();
        while (!node.leaf) {
            if (!visited.add(node.id)) throw new StorageException(IO_ERROR, "B+Tree child cycle");
            int child = 0;
            if (!fromLeft) {
                while (child < node.entries.size() && keys.compare(key, node.entries.get(child).key()) > 0) child++;
            }
            node = readNode(node.children.get(child));
        }
        return node;
    }

    private void change(Value key, RID rid, long lsn, boolean inserting) {
        if (lsn <= 0) throw new StorageException(INVALID_VALUE, "Mutation needs positive LSN");
        IndexHelper.Entry entry = keys.createEntry(key, rid, tableId);
        readDescriptor();
        if (inserting && entry.byteSize() > maxEntrySize) throw new StorageException(INVALID_VALUE, "B+Tree key is too large");
        try {
            Node root = changeNode(rootPageId.pageNumber(), entry, inserting, new HashSet<>());
            if (root == null) return;
            if (!root.leaf && root.children.size() == 1) writeDescriptor(root.children.get(0));
            else {
                Long right = storeNode(root);
                if (right != null) {
                    Node parent = new Node(allocatePage(), false);
                    parent.children.add(root.id);
                    parent.children.add(right);
                    refresh(parent);
                    writeNode(parent);
                    writeDescriptor(parent.id);
                }
            }
            commitChanges(lsn);
        } finally { changes.clear(); }
    }

    // DELETE juga dapat membesarkan separator; parent menanganinya seperti split saat INSERT.
    private Node changeNode(long id, IndexHelper.Entry entry, boolean inserting, Set<Long> path) {
        if (!path.add(id)) throw new StorageException(IO_ERROR, "B+Tree child cycle");
        Node node = readNode(id);
        int position = 0, high = node.entries.size();
        while (position < high) {
            int middle = (position + high) >>> 1;
            if (compare(node.entries.get(middle), entry) <= 0) position = middle + 1;
            else high = middle;
        }
        if (node.leaf) {
            boolean exists = position > 0 && compare(node.entries.get(position - 1), entry) == 0;
            if (inserting == exists) return null;
            if (inserting) node.entries.add(position, entry);
            else node.entries.remove(position - 1);
        } else {
            Node child = changeNode(node.children.get(position), entry, inserting, path);
            if (child == null) return null;
            Long right = storeNode(child);
            if (right != null) node.children.add(position + 1, right);
            else if (!inserting) rebalance(node, position);
            refresh(node);
        }
        return node;
    }

    private Long storeNode(Node node) {
        if (fits(node)) { writeNode(node); return null; }
        long rightId = allocatePage();
        Node right = node.leaf ? splitLeaf(node, rightId) : splitInternal(node, rightId);
        writeNode(right);
        writeNode(node);
        return right.id;
    }

    private void rebalance(Node parent, int child) {
        if (parent.children.size() == 1) return;
        Node changed = readNode(parent.children.get(child));
        if (byteSize(changed) >= HEADER + (pageSize - HEADER) / 2
                && (changed.leaf || changed.children.size() >= 2)) return;
        int leftPosition = child == 0 ? 0 : child - 1;
        Node left = readNode(parent.children.get(leftPosition));
        Node right = readNode(parent.children.get(leftPosition + 1));
        if (left.leaf != right.leaf) throw new StorageException(IO_ERROR, "B+Tree siblings have different levels");
        Node combined = new Node(left.id, left.leaf);
        combined.next = right.next;
        combined.entries.addAll(left.entries);
        combined.entries.addAll(right.entries);
        combined.children.addAll(left.children);
        combined.children.addAll(right.children);
        if (!combined.leaf) refresh(combined);
        if (fits(combined)) {
            writeNode(combined);
            parent.children.remove(leftPosition + 1);
            // shortcut: page yang dilepas direklamasi saat rebuild; perlu free-page list jika ukuran file menjadi masalah.
        } else {
            Node redistributed = combined.leaf ? splitLeaf(combined, right.id) : splitInternal(combined, right.id);
            writeNode(combined);
            writeNode(redistributed);
        }
    }

    // Node sudah dibaca oleh pemanggil; hasil kanan dipakai untuk memperbarui parent.
    private Node splitLeaf(Node left, long rightId) {
        int boundary = findSplitPoint(left);
        Node right = new Node(rightId, true);
        right.next = left.next;
        right.entries.addAll(left.entries.subList(boundary, left.entries.size()));
        left.entries.subList(boundary, left.entries.size()).clear();
        left.next = right.id;
        return right;
    }

    private Node splitInternal(Node left, long rightId) {
        int boundary = findSplitPoint(left);
        Node right = new Node(rightId, false);
        right.children.addAll(left.children.subList(boundary, left.children.size()));
        right.entries.addAll(left.entries.subList(boundary, left.entries.size()));
        left.children.subList(boundary, left.children.size()).clear();
        left.entries.subList(boundary - 1, left.entries.size()).clear();
        return right;
    }

    private int findSplitPoint(Node node) {
        int boundary = -1, balance = Integer.MAX_VALUE;
        int total = byteSize(node), left = HEADER + (node.leaf ? 0 : 8);
        int first = node.leaf ? 1 : 2;
        int last = node.leaf ? node.entries.size() - 1 : node.children.size() - 2;
        for (int i = 1; i <= last; i++) {
            if (node.leaf) left += node.entries.get(i - 1).byteSize();
            else if (i > 1) left += 8 + node.entries.get(i - 2).byteSize();
            if (i < first) continue;
            int right = total - left + HEADER - (node.leaf ? 0 : node.entries.get(i - 1).byteSize());
            if (!node.leaf && (i > order || node.children.size() - i > order)) continue;
            int difference = Math.abs(left - right);
            if (left <= pageSize && right <= pageSize && difference < balance) { boundary = i; balance = difference; }
        }
        if (boundary < 0) throw new StorageException(INVALID_VALUE, "No legal B+Tree page split");
        return boundary;
    }

    private int compare(IndexHelper.Entry a, IndexHelper.Entry b) {
        return keys.compareEntry(a.key(), a.rid(), b.key(), b.rid());
    }

    private void refresh(Node node) {
        node.entries.clear();
        for (int i = 1; i < node.children.size(); i++) {
            Set<Long> visited = new HashSet<>();
            Node child = readNode(node.children.get(i));
            while (!child.leaf) {
                if (!visited.add(child.id)) throw new StorageException(IO_ERROR, "B+Tree child cycle");
                child = readNode(child.children.get(0));
            }
            if (child.entries.isEmpty()) throw new StorageException(IO_ERROR, "Empty non-root subtree");
            node.entries.add(child.entries.get(0));
        }
    }

    private int byteSize(Node node) {
        return HEADER + node.entries.stream().mapToInt(IndexHelper.Entry::byteSize).sum()
                + (node.leaf ? 0 : Long.BYTES * node.children.size());
    }

    private boolean fits(Node node) { return byteSize(node) <= pageSize && (node.leaf || node.children.size() <= order); }

    private Node readNode(long id) {
        ByteBuffer in = readPage(id, -1);
        int type = in.getInt(16), count = in.getInt(20);
        if (type != LEAF && type != BRANCH) throw new StorageException(IO_ERROR, "Invalid B+Tree node type");
        Node node = new Node(id, type == LEAF);
        node.next = in.getLong(24);
        long pageCount = buffer.getPageCount(storageId);
        if (node.next != -1 && (node.next <= 0 || node.next >= pageCount)) throw new StorageException(IO_ERROR, "Invalid leaf link");
        if (count < 0 || count > (in.remaining() - (node.leaf ? 0 : 8)) / (node.leaf ? 16 : 24)) {
            throw new StorageException(IO_ERROR, "Invalid B+Tree entry count");
        }
        if (!node.leaf) node.children.add(in.getLong());
        for (int i = 0; i < count; i++) {
            IndexHelper.Entry entry = keys.readEntry(in, tableId);
            if (entry.byteSize() > maxEntrySize || i > 0 && compare(node.entries.get(i - 1), entry) >= 0) {
                throw new StorageException(IO_ERROR, "Invalid B+Tree entry ordering/size");
            }
            node.entries.add(entry);
            if (!node.leaf) {
                if (in.remaining() < Long.BYTES) throw new StorageException(IO_ERROR, "Truncated child pointer");
                node.children.add(in.getLong());
            }
        }
        Set<Long> children = new HashSet<>();
        for (long child : node.children) {
            if (child <= 0 || child >= pageCount || child == id || !children.add(child)) throw new StorageException(IO_ERROR, "Invalid B+Tree child pointer");
        }
        return node;
    }

    private void writeNode(Node node) {
        ByteBuffer out = pageImage(node.leaf ? LEAF : BRANCH, node.entries.size(), node.next);
        if (!node.leaf) out.putLong(node.children.get(0));
        for (int i = 0; i < node.entries.size(); i++) {
            IndexHelper.writeEntry(out, node.entries.get(i));
            if (!node.leaf) out.putLong(node.children.get(i + 1));
        }
        changes.put(node.id, out);
    }

    private long allocatePage() {
        Page page = buffer.newPage(storageId);
        try { configure(page.getData().length); return page.getPageId().pageNumber(); }
        finally { buffer.unpinPage(page.getPageId()); }
    }

    private ByteBuffer readPage(long number, int type) {
        if (number < 0 || number >= buffer.getPageCount(storageId)) throw new StorageException(IO_ERROR, "Invalid index page pointer " + number);
        PageId id = new PageId(storageId, number);
        ByteBuffer in = changes.get(number);
        if (in == null) {
            Page page = buffer.fetchPage(id);
            try { configure(page.getData().length); in = ByteBuffer.wrap(page.getData().clone()); }
            finally { buffer.unpinPage(id); }
        } else in = in.duplicate();
        if (in.getLong(0) < 0 || in.getInt(8) != INDEX_PAGE_SIGNATURE || in.getInt(12) != 1
                || (type >= 0 && in.getInt(16) != type) || in.getInt(16) < 0 || in.getInt(16) > BRANCH) {
            throw new StorageException(IO_ERROR, "Invalid index page header: " + id);
        }
        in.position(HEADER);
        return in;
    }

    private ByteBuffer pageImage(int type, int count, long link) {
        ByteBuffer out = ByteBuffer.allocate(pageSize).order(ByteOrder.BIG_ENDIAN);
        out.putLong(0).putInt(INDEX_PAGE_SIGNATURE).putInt(1).putInt(type).putInt(count).putLong(link);
        return out;
    }

    // shortcut: rollback hanya lokal; crash dan flush eksternal memerlukan koordinasi SM1/FRM.
    private void commitChanges(long lsn) {
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

    private void writeDescriptor(long root) {
        ByteBuffer out = pageImage(0, 0, -1);
        out.putInt(identity.length).put(identity).putLong(root).putInt(order);
        changes.put(0L, out);
        rootPageId = new PageId(storageId, root);
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
        if (config != order) throw new StorageException(IO_ERROR, "B+Tree order mismatch");
        rootPageId = new PageId(storageId, root);
    }

    private static final class Node {
        final long id;
        final boolean leaf;
        long next = -1;
        final List<IndexHelper.Entry> entries = new ArrayList<>();
        final List<Long> children = new ArrayList<>();
        Node(long id, boolean leaf) { this.id = id; this.leaf = leaf; }
    }
}
