package sqlike.replacement_durability;

import java.util.Optional;

import sqlike.shared.PageId;

/** Decides WHO to evict. Does not know about dirty pages, files or WAL. */
public interface ReplacementPolicy {
    void recordAccess(PageId pageId);
    /** false while pinned, true when pinCount drops to 0. */
    void setEvictable(PageId pageId, boolean evictable);
    /** Empty if every page is pinned. */
    Optional<PageId> chooseVictim();
    // TODO (maybe): void remove(PageId pageId);  // needed when a frame is dropped
}