package sqlike.replacement_durability;

import java.util.Optional;
import java.util.function.Predicate;

import sqlike.shared.PageId;

/** Decides WHO to evict. Does not know about dirty pages, files or WAL. */
public interface ReplacementPolicy {
    void recordAccess(PageId pageId);

    Optional<PageId> chooseVictim(Predicate<PageId> evictable);

    void remove(PageId pageId);

    void clear();
}