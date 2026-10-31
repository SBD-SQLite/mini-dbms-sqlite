package sqlike.qp;

import sqlike.contract.IStorageManager;
import sqlike.shared.ErrorCode;

public class MutationCoordinator {
    private final IStorageManager storage;

    public MutationCoordinator(IStorageManager storage) {
        this.storage = storage;
    }

    public IStorageManager getStorage() {
        return storage;
    }

    public void delete(String tableName, StoredRecord before, ExecutionContext context) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name cannot be null or blank in MutationCoordinator.delete");
        }
        if (before == null) {
            throw new IllegalArgumentException("Before-image StoredRecord cannot be null in MutationCoordinator.delete");
        }
        if (context == null) {
            throw new IllegalArgumentException("ExecutionContext cannot be null in MutationCoordinator.delete");
        }
        if (storage != null) {
            try {
                storage.delete(tableName, before.rid(), context.txId());
            } catch (QpException e) {
                throw e;
            } catch (Exception e) {
                throw new QpException(ErrorCode.IO_ERROR, "Failed to delete record via storage stub: " + e.getMessage());
            }
        }
    }
}
