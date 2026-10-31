package sqlike.qp;

import sqlike.contract.IStorageManager;

/**
 * MutationCoordinator stub matching CLASS DIAGRAM FIX (owned by QP5 role).
 * Provided as a mock placeholder for Milestone 1 dependency wiring.
 */
public class MutationCoordinator {
    private final IStorageManager storage;

    public MutationCoordinator(IStorageManager storage) {
        // TODO(M1-MOCK): Store storage manager reference for mutation operations
        this.storage = storage;
    }

    public IStorageManager getStorage() {
        return storage;
    }
}
