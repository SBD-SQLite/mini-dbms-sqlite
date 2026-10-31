package storage.Replacement_Durability;

import java.util.LinkedHashMap;
import java.util.Optional;

public class LRUReplacementPolicy implements ReplacementPolicy {
    private final LinkedHashMap<PageId, Boolean> accessOrder = new LinkedHashMap<>();

    @Override public void recordAccess(PageId pageId){
        throw new UnsupportedOperationException("TODO");
    }

    @Override public void setEvictable(PageId pageId, boolean evictable){
        throw new UnsupportedOperationException("TODO");
    }

    @Override public Optional<PageId> chooseVictim(){
        throw new UnsupportedOperationException("TODO");
    }
}
