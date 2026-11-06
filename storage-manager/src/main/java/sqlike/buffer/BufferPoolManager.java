package sqlike.buffer

import sqlike.block.*;

public class BufferPoolManager{
    private BlockManager blocks;
    private IWalDurability wal;
    private Map<PageId, BufferFrame> frames;
    private int capacity;
    private ReplacementPolicy Replacement;
}
