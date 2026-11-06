package sqlike.record;

import java.util.Optional;
import sqlike.block.Page;

public class SlottedPage {
    private static final int HEADER_SIZE = 16;
    private static final int SLOT_SIZE = 9;

    private Page page;

    public SlottedPage(Page page) {
        this.page = page;
    }

    public Page getPage() {
        return page;
    }

    public void setPage(Page page) {
        this.page = page;
    }

    public void initialize() {
        throw new UnsupportedOperationException("TODO");
    }

    public long getPageLSN() {
        throw new UnsupportedOperationException("TODO");
    }

    public void setPageLSN(long lsn) {
        throw new UnsupportedOperationException("TODO");
    }

    public int getSlotCount() {
        throw new UnsupportedOperationException("TODO");
    }

    public SlotStatus getSlotStatus(int slotId) {
        throw new UnsupportedOperationException("TODO");
    }

    public int getFreeSpace() {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean canFit(int recordSize) {
        throw new UnsupportedOperationException("TODO");
    }

    public Optional<Integer> reserveSlot(int recordSize) {
        throw new UnsupportedOperationException("TODO");
    }

    public void releaseSlot(int slotId) {
        throw new UnsupportedOperationException("TODO");
    }

    public void insertAtSlot(int slotId, byte[] recordBytes) {
        throw new UnsupportedOperationException("TODO");
    }

    public Optional<byte[]> getRecordBytes(int slotId) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean updateRecordBytes(int slotId, byte[] recordBytes) {
        throw new UnsupportedOperationException("TODO");
    }

    public void putAtSlot(int slotId, byte[] recordBytes) {
        throw new UnsupportedOperationException("TODO");
    }

    public void deleteSlot(int slotId) {
        throw new UnsupportedOperationException("TODO");
    }

    private void compact() {
        throw new UnsupportedOperationException("TODO");
    }
}
