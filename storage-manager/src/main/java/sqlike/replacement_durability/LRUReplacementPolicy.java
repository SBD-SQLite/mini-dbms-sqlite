package sqlike.replacement_durability;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Predicate;

import sqlike.shared.PageId;

public class LRUReplacementPolicy implements ReplacementPolicy {
    private final LinkedHashMap<PageId, Boolean> accessOrder = new LinkedHashMap<>();

    @Override public void recordAccess(PageId pageId){
        accessOrder.remove(pageId);
        accessOrder.put(pageId, Boolean.TRUE);
    }

    @Override
    public Optional<PageId> chooseVictim(Predicate<PageId> evictable) {
        for (PageId id : accessOrder.keySet()) {
            if (evictable.test(id)) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    @Override
    public void remove(PageId pageId) { 
        accessOrder.remove(pageId); 
    }

    @Override
    public void clear() { 
        accessOrder.clear(); 
    }
}
