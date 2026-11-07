package sqlike.index;

import sqlike.buffer.BufferPoolManager;
import sqlike.shared.IndexMetadata;
import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.Value;
import sqlike.storage.StorageException;
import java.util.List;

import static sqlike.storage.StorageErrorCode.INVALID_STATE;

public class HashIndex implements Index {
    private BufferPoolManager buffer;
    private IndexMetadata indexMetadata;
    private PageId directoryPageId;
    private int bucketCount;

    private final IndexHelper keys;

    public HashIndex(BufferPoolManager buffer, IndexMetadata indexMetadata, IndexHelper keys) {
        this.buffer = buffer;
        this.indexMetadata = indexMetadata;
        this.keys = keys;
    }

    void initialize(long lsn) throws StorageException {
        throw new StorageException(INVALID_STATE, "HashIndex page initialization is not implemented");
    }

    void discard() throws StorageException {
        if (directoryPageId != null) {
            throw new StorageException(INVALID_STATE, "HashIndex page cleanup is not implemented");
        }
    }

    // Gunakan keys.hash dan Math.floorMod dengan bucketCount agar hash negatif tetap menghasilkan bucket valid.
    private int hashKey(Value key) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    private PageId getBucketPage(int bucketId) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    private PageId allocateOverflowPage(int bucketId, long lsn) throws StorageException {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public List<RID> search(Value key) throws StorageException {
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
