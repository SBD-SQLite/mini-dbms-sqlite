package sqlike.index;

import sqlike.buffer.BufferPoolManager;
import sqlike.shared.IndexCondition;
import sqlike.shared.IndexMetadata;

public class BPlusTree implements Index {
    private int order;
    private BufferPoolManager buffer;
    private IndexMetadata indexMetadata;

    private void findLeaf() {
        throw new UnsupportedOperationException("TODO");
    }

    private void splitLeaf() {
        throw new UnsupportedOperationException("TODO");
    }

    private void splitInternal() {
        throw new UnsupportedOperationException("TODO");
    }

    private void rebalance() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void search() {
        throw new UnsupportedOperationException("TODO");
    }

    public void searchRange(IndexCondition condition) {
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
