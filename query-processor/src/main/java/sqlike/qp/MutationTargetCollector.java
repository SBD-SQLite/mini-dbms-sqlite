package sqlike.qp;

import sqlike.contract.IStorageManager;

/**
 * MutationTargetCollector stub matching CLASS DIAGRAM FIX (owned by QP5 role).
 * Provided as a mock placeholder for Milestone 1 dependency wiring.
 */
public class MutationTargetCollector {
    private final IStorageManager storage;

    public MutationTargetCollector(IStorageManager storage) {
        // TODO(M1-MOCK): Store storage manager reference
        this.storage = storage;
    }

    public IStorageManager getStorage() {
        return storage;
    }
}
