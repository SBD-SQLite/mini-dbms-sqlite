package sqlike.qp;

import java.util.Objects;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.StubRuntime.AbstractCommandExecutor;

public final class CreateIndexExecutor extends AbstractCommandExecutor {
    private final CreateIndexSpec spec;
    private final IStorageManager storage;
    public CreateIndexExecutor(CreateIndexSpec spec, IStorageManager storage) {
        this.spec = Objects.requireNonNull(spec); this.storage = Objects.requireNonNull(storage);
    }
    @Override protected long doExecute(ExecutionContext context) {
        storage.createIndex(spec, context, context.requireGrant(spec.tableId(), AccessMode.WRITE)); return 0;
    }
    @Override protected void doClose() { }
}
