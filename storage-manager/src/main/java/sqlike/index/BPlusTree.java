package sqlike.index;

import sqlike.buffer.BufferPoolManager;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.Value;
import sqlike.storage.StorageException;
import java.util.List;

import static sqlike.storage.StorageErrorCode.INVALID_STATE;

public class BPlusTree implements Index {
    private int order;
    private BufferPoolManager buffer;
    private IndexMetadata indexMetadata;
    private PageId rootPageId;

    private final IndexHelper keys;

    public BPlusTree(BufferPoolManager buffer, IndexMetadata indexMetadata, IndexHelper keys) {
        this.buffer = buffer;
        this.indexMetadata = indexMetadata;
        this.keys = keys;
    }

    void initialize(long lsn) throws StorageException {
        throw new StorageException(INVALID_STATE, "BPlusTree page initialization is not implemented");
    }

    void discard() throws StorageException {
        if (rootPageId != null) {
            throw new StorageException(INVALID_STATE, "BPlusTree page cleanup is not implemented");
        }
    }

    private PageId findLeaf(Value key) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    private void splitLeaf(PageId pageId, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    private void splitInternal(PageId pageId, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    private void rebalance(PageId pageId, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public List<RID> search(Value key) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    public List<RID> searchRange(IndexCondition condition) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void insert(Value key, RID rid, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void remove(Value key, RID rid, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void flush() throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }
}
