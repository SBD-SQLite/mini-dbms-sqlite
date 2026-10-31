package sqlike.qp;

import java.util.Objects;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;
import sqlike.storage.StorageException;

public final class CreateIndexExecutor extends AbstractCommandExecutor {
    private final CreateIndexSpec spec;
    private final sqlike.contract.IStorageManager storageContract;
    private final sqlike.qp.port.QpContracts.IStorageManager qpStorage;

    public CreateIndexExecutor(CreateIndexSpec spec, sqlike.contract.IStorageManager storage) {
        super();
        this.spec = Objects.requireNonNull(spec);
        this.storageContract = Objects.requireNonNull(storage);
        this.qpStorage = null;
    }

    public CreateIndexExecutor(CreateIndexSpec spec, sqlike.qp.port.QpContracts.IStorageManager storage) {
        super();
        this.spec = Objects.requireNonNull(spec);
        this.storageContract = null;
        this.qpStorage = Objects.requireNonNull(storage);
    }

    public CreateIndexSpec getSpec() {
        return spec;
    }

    @Override
    protected long doExecute(ExecutionContext context) {
        if (!context.implicit()) {
            throw failure("CREATE INDEX is not allowed within an explicit transaction");
        }
        context.requireGrant(spec.tableId(), AccessMode.WRITE);
        if (qpStorage != null) {
            qpStorage.createIndex(spec, context, context.requireGrant(spec.tableId(), AccessMode.WRITE));
        } else if (storageContract != null) {
            try {
                storageContract.createIndex(spec.definition());
            } catch (StorageException e) {
                throw new QpException(sqlike.shared.ErrorCode.IO_ERROR, "Failed to create index: " + e.getMessage());
            }
        }
        return 0;
    }

    @Override
    protected void doClose() { }
}
