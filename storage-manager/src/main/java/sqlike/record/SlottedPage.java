package sqlike.record;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import sqlike.block.Page;

public class SlottedPage {
    private static final int HEADER_SIZE = 16;
    private static final int SLOT_SIZE = 9;
    private static final int SLOT_COUNT_OFFSET = 8;
    private static final int FREE_END_OFFSET = 12;

    private Page page;

    public SlottedPage(Page page) {
        setPage(page);
    }

    public Page getPage() {
        return page;
    }

    public void setPage(Page page) {
        Objects.requireNonNull(page, "page");
        if (page.getData() == null || page.getData().length < HEADER_SIZE) {
            throw new IllegalArgumentException("Page must have at least 16 bytes");
        }
        this.page = page;
        page.setPageLSN(ByteBuffer.wrap(page.getData()).order(ByteOrder.BIG_ENDIAN).getLong(0));
    }

    public void initialize() {
        Arrays.fill(page.getData(), (byte) 0);
        ByteBuffer.wrap(page.getData()).order(ByteOrder.BIG_ENDIAN)
                .putInt(FREE_END_OFFSET, page.getData().length);
        page.setPageLSN(0);
    }

    public long getPageLSN() {
        return buffer().getLong(0);
    }

    public void setPageLSN(long lsn) {
        if (lsn <= 0) throw new IllegalArgumentException("LSN must be positive");
        ByteBuffer data = buffer();
        long next = Math.max(data.getLong(0), lsn);
        data.putLong(0, next);
        page.setPageLSN(next);
    }

    public int getSlotCount() {
        return buffer().getInt(SLOT_COUNT_OFFSET);
    }

    public SlotStatus getSlotStatus(int slotId) {
        ByteBuffer data = buffer();
        checkSlot(data, slotId);
        return status(data, slotId);
    }

    public int getFreeSpace() {
        ByteBuffer data = buffer();
        return data.getInt(FREE_END_OFFSET) - directoryEnd(data);
    }

    static boolean fitsEmptyPage(int pageSize, int recordSize) {
        return (long) allocationSize(recordSize) + HEADER_SIZE + SLOT_SIZE <= pageSize;
    }

    public boolean canFit(int recordSize) {
        int capacity = allocationSize(recordSize);
        ByteBuffer data = buffer();
        int overhead = emptySlot(data) < 0 ? SLOT_SIZE : 0;
        return (long) capacity + overhead <= getFreeSpace();
    }

    public Optional<Integer> reserveSlot(int recordSize) {
        int capacity = allocationSize(recordSize);
        ByteBuffer data = buffer();
        int slotId = emptySlot(data);
        boolean append = slotId < 0;
        if ((long) capacity + (append ? SLOT_SIZE : 0) > getFreeSpace()) {
            return Optional.empty();
        }
        if (append) {
            slotId = data.getInt(SLOT_COUNT_OFFSET);
            data.putInt(SLOT_COUNT_OFFSET, slotId + 1);
        }
        int offset = data.getInt(FREE_END_OFFSET) - capacity;
        data.putInt(FREE_END_OFFSET, offset);
        writeSlot(data, slotId, offset, 0, SlotStatus.RESERVED);
        Arrays.fill(page.getData(), offset, offset + capacity, (byte) 0);
        return Optional.of(slotId);
    }

    public void releaseSlot(int slotId) {
        SlotStatus current = getSlotStatus(slotId);
        if (current == SlotStatus.EMPTY) return;
        if (current != SlotStatus.RESERVED) {
            throw new IllegalStateException("Only an unconsumed reservation can be released");
        }
        repack(slotId, 0, SlotStatus.EMPTY, 0, 0);
    }

    public void insertAtSlot(int slotId, byte[] recordBytes) {
        Objects.requireNonNull(recordBytes, "recordBytes");
        ByteBuffer data = buffer();
        checkSlot(data, slotId);
        if (status(data, slotId) != SlotStatus.RESERVED) {
            throw new IllegalStateException("Insert requires a reserved slot");
        }
        if (recordBytes.length > capacity(data, slotId)) {
            throw new IllegalArgumentException("Record exceeds its reservation");
        }
        writeRecord(data, slotId, recordBytes, SlotStatus.OCCUPIED);
    }

    public Optional<byte[]> getRecordBytes(int slotId) {
        if (slotId < 0) throw new IllegalArgumentException("Slot must not be negative");
        ByteBuffer data = buffer();
        if (slotId >= data.getInt(SLOT_COUNT_OFFSET) || status(data, slotId) != SlotStatus.OCCUPIED) {
            return Optional.empty();
        }
        capacity(data, slotId);
        int offset = data.getInt(slotOffset(slotId));
        int length = data.getInt(slotOffset(slotId) + 4);
        return Optional.of(Arrays.copyOfRange(page.getData(), offset, offset + length));
    }

    public boolean updateRecordBytes(int slotId, byte[] recordBytes) {
        Objects.requireNonNull(recordBytes, "recordBytes");
        ByteBuffer data = buffer();
        checkSlot(data, slotId);
        SlotStatus current = status(data, slotId);
        if (current != SlotStatus.OCCUPIED && current != SlotStatus.MOVED) {
            throw new IllegalStateException("Update requires an occupied slot");
        }
        int previous = capacity(data, slotId);
        int required = allocationSize(recordBytes.length);
        if (required > previous) {
            if ((long) required - previous > getFreeSpace()) return false;
            repack(slotId, required, current, 0, 0);
        }
        writeRecord(buffer(), slotId, recordBytes, current);
        return true;
    }

    public void putAtSlot(int slotId, byte[] recordBytes) {
        Objects.requireNonNull(recordBytes, "recordBytes");
        if (slotId < 0) throw new IllegalArgumentException("Slot must not be negative");
        ByteBuffer data = buffer();
        int count = data.getInt(SLOT_COUNT_OFFSET);
        int required = allocationSize(recordBytes.length);
        if (slotId < count && status(data, slotId) == SlotStatus.FORWARD) {
            throw new IllegalStateException("Forwarded slot must be updated at its target");
        }
        if (slotId < count && allocated(status(data, slotId))) {
            int previous = capacity(data, slotId);
            if (required > previous) {
                if ((long) required - previous > getFreeSpace()) {
                    throw new IllegalArgumentException("Restored record does not fit in page");
                }
                repack(slotId, required, status(data, slotId), 0, 0);
            }
        } else {
            long extraSlots = Math.max(0L, (long) slotId + 1 - count);
            if (extraSlots * SLOT_SIZE + required > getFreeSpace()) {
                throw new IllegalArgumentException("Restored slot does not fit in page");
            }
            for (int i = count; i <= slotId; i++) {
                writeSlot(data, i, 0, 0, SlotStatus.EMPTY);
            }
            data.putInt(SLOT_COUNT_OFFSET, (int) (count + extraSlots));
            int offset = data.getInt(FREE_END_OFFSET) - required;
            data.putInt(FREE_END_OFFSET, offset);
            writeSlot(data, slotId, offset, 0, SlotStatus.RESERVED);
        }
        writeRecord(buffer(), slotId, recordBytes, SlotStatus.OCCUPIED);
    }

    public void deleteSlot(int slotId) {
        ByteBuffer data = buffer();
        checkSlot(data, slotId);
        SlotStatus current = status(data, slotId);
        switch (current) {
            case EMPTY, DELETED -> { }
            case OCCUPIED -> repack(slotId, 0, SlotStatus.DELETED, 0, 0);
            case MOVED -> repack(slotId, 0, SlotStatus.EMPTY, 0, 0);
            case FORWARD -> writeSlot(data, slotId, 0, 0, SlotStatus.DELETED);
            default -> throw new IllegalStateException("A reservation must be released, not deleted");
        }
    }

    public Optional<long[]> getForward(int slotId) {
        if (slotId < 0) throw new IllegalArgumentException("Slot must not be negative");
        ByteBuffer data = buffer();
        if (slotId >= data.getInt(SLOT_COUNT_OFFSET) || status(data, slotId) != SlotStatus.FORWARD) return Optional.empty();
        return Optional.of(new long[] {data.getInt(slotOffset(slotId)), data.getInt(slotOffset(slotId) + 4)});
    }

    public Optional<byte[]> getMovedBytes(int slotId) {
        if (slotId < 0) throw new IllegalArgumentException("Slot must not be negative");
        ByteBuffer data = buffer();
        if (slotId >= data.getInt(SLOT_COUNT_OFFSET) || status(data, slotId) != SlotStatus.MOVED) return Optional.empty();
        capacity(data, slotId);
        int offset = data.getInt(slotOffset(slotId));
        int length = data.getInt(slotOffset(slotId) + 4);
        return Optional.of(Arrays.copyOfRange(page.getData(), offset, offset + length));
    }

    public void markMoved(int slotId) {
        ByteBuffer data = buffer();
        checkSlot(data, slotId);
        if (status(data, slotId) != SlotStatus.OCCUPIED) throw new IllegalStateException("Only an occupied slot can be marked moved");
        data.put(slotOffset(slotId) + 8, (byte) SlotStatus.MOVED.ordinal());
    }

    public void forwardSlot(int slotId, long targetPage, int targetSlot) {
        if (slotId < 0 || targetPage < 0 || targetPage > Integer.MAX_VALUE || targetSlot < 0) {
            throw new IllegalArgumentException("Invalid forward target");
        }
        ByteBuffer data = buffer();
        int count = data.getInt(SLOT_COUNT_OFFSET);
        if (slotId >= count) {
            long extra = (long) slotId + 1 - count;
            if (extra * SLOT_SIZE > getFreeSpace()) throw new IllegalArgumentException("Slot directory does not fit in page");
            for (int i = count; i <= slotId; i++) writeSlot(data, i, 0, 0, SlotStatus.EMPTY);
            data.putInt(SLOT_COUNT_OFFSET, slotId + 1);
        }
        SlotStatus current = status(data, slotId);
        if (current == SlotStatus.OCCUPIED) repack(slotId, 0, SlotStatus.FORWARD, (int) targetPage, targetSlot);
        else if (current == SlotStatus.DELETED || current == SlotStatus.EMPTY || current == SlotStatus.FORWARD) {
            writeSlot(data, slotId, (int) targetPage, targetSlot, SlotStatus.FORWARD);
        } else throw new IllegalStateException("Slot cannot be forwarded: " + current);
    }

    private void repack(int changedSlot, int newCapacity, SlotStatus newStatus, int f1, int f2) {
        ByteBuffer source = buffer();
        byte[] packed = new byte[page.getData().length];
        System.arraycopy(page.getData(), 0, packed, 0, directoryEnd(source));
        ByteBuffer target = ByteBuffer.wrap(packed).order(ByteOrder.BIG_ENDIAN);
        int end = packed.length;
        for (int i = 0; i < source.getInt(SLOT_COUNT_OFFSET); i++) {
            SlotStatus current = status(source, i);
            if (i == changedSlot && newCapacity == 0) {
                writeSlot(target, i, f1, f2, newStatus);
                continue;
            }
            if (current == SlotStatus.DELETED) {
                writeSlot(target, i, 0, 0, SlotStatus.DELETED);
                continue;
            }
            if (!allocated(current)) continue;
            int previous = capacity(source, i);
            int allocatedBytes = i == changedSlot ? newCapacity : previous;
            int length = source.getInt(slotOffset(i) + 4);
            end -= allocatedBytes;
            System.arraycopy(page.getData(), source.getInt(slotOffset(i)), packed, end, length);
            writeSlot(target, i, end, length, current);
        }
        target.putInt(FREE_END_OFFSET, end);
        System.arraycopy(packed, 0, page.getData(), 0, packed.length);
    }

    private static boolean allocated(SlotStatus status) {
        return status == SlotStatus.RESERVED || status == SlotStatus.OCCUPIED || status == SlotStatus.MOVED;
    }

    private ByteBuffer buffer() {
        ByteBuffer data = ByteBuffer.wrap(page.getData()).order(ByteOrder.BIG_ENDIAN);
        int count = data.getInt(SLOT_COUNT_OFFSET);
        if (data.getLong(0) < 0 || count < 0 || count > (data.capacity() - HEADER_SIZE) / SLOT_SIZE
                || data.getInt(FREE_END_OFFSET) < HEADER_SIZE + count * SLOT_SIZE
                || data.getInt(FREE_END_OFFSET) > data.capacity()) {
            throw new IllegalStateException("Invalid slotted page header; initialize new pages first");
        }
        return data;
    }

    private static int directoryEnd(ByteBuffer data) {
        return HEADER_SIZE + data.getInt(SLOT_COUNT_OFFSET) * SLOT_SIZE;
    }

    private static int slotOffset(int slotId) {
        return HEADER_SIZE + slotId * SLOT_SIZE;
    }

    private static void checkSlot(ByteBuffer data, int slotId) {
        if (slotId < 0 || slotId >= data.getInt(SLOT_COUNT_OFFSET)) {
            throw new IllegalArgumentException("Slot does not exist: " + slotId);
        }
    }

    private static SlotStatus status(ByteBuffer data, int slotId) {
        int code = data.get(slotOffset(slotId) + 8);
        if (code < 0 || code >= SlotStatus.values().length) {
            throw new IllegalStateException("Invalid slot status: " + code);
        }
        return SlotStatus.values()[code];
    }

     private static int emptySlot(ByteBuffer data) {
        int deleted = -1;
        for (int i = 0; i < data.getInt(SLOT_COUNT_OFFSET); i++) {
            SlotStatus current = status(data, i);
            if (current == SlotStatus.EMPTY) return i;
            if (current == SlotStatus.DELETED && deleted < 0) deleted = i;
        }
        return deleted;
    }

    private static int capacity(ByteBuffer data, int slotId) {
        int offset = data.getInt(slotOffset(slotId));
        int end = data.capacity();
        for (int i = 0; i < data.getInt(SLOT_COUNT_OFFSET); i++) {
            if (i == slotId || !allocated(status(data, i))) continue;
            int other = data.getInt(slotOffset(i));
            if (other == offset) throw new IllegalStateException("Overlapping slot allocations");
            if (other > offset && other < end) end = other;
        }
        int length = data.getInt(slotOffset(slotId) + 4);
        if (offset < data.getInt(FREE_END_OFFSET) || offset >= data.capacity()
                || length < 0 || length > end - offset) {
            throw new IllegalStateException("Invalid slot bounds: " + slotId);
        }
        return end - offset;
    }

    private void writeRecord(ByteBuffer data, int slotId, byte[] bytes, SlotStatus status) {
        int offset = data.getInt(slotOffset(slotId));
        System.arraycopy(bytes, 0, page.getData(), offset, bytes.length);
        Arrays.fill(page.getData(), offset + bytes.length, offset + capacity(data, slotId), (byte) 0);
        writeSlot(data, slotId, offset, bytes.length, status);
    }

    private static void writeSlot(ByteBuffer data, int slotId, int offset, int length, SlotStatus status) {
        int start = slotOffset(slotId);
        data.putInt(start, offset);
        data.putInt(start + 4, length);
        data.put(start + 8, (byte) status.ordinal());
    }

    private static int allocationSize(int recordSize) {
        if (recordSize < 0) throw new IllegalArgumentException("Record size must not be negative");
        return Math.max(1, recordSize);
    }
}
