package sqlike.buffer;

import sqlike.block.*;
import sqlike.replacement_durability.*;
import sqlike.shared.*;
import sqlike.replacement_durability.*;
import java.util.*;

public class BufferPoolManager{
    private BlockManager blocks;
    private IWalDurability wal;
    private Map<PageId, BufferFrame> frames;
    private int capacity;
    private ReplacementPolicy Replacement;
}
