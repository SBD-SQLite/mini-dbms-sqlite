package sqlike.index;

import sqlike.buffer.BufferPoolManager;
import sqlike.shared.IndexMetadata;

public class HashIndex implements Index {
    private BufferPoolManager buffer;
    private IndexMetadata indexMetadata;
    // private PageId directoryPageId;
    private int bucketCount;

    private int hashKey() {
        throw new UnsupportedOperationException("TODO");
    }

    private void getBucketPage(int bucketId) {
        throw new UnsupportedOperationException("TODO");
    }

    private void allocateOverflowPage(int bucketId) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void search() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void insert() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void flush() {
        throw new UnsupportedOperationException("TODO");
    }
}
