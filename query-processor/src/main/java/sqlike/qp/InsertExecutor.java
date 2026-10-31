package sqlike.qp;

import java.util.Objects;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.StubRuntime.AbstractCommandExecutor;

public final class InsertExecutor extends AbstractCommandExecutor {
    private final InsertSpec spec;
    private final MutationCoordinator mutations;
    public InsertExecutor(InsertSpec spec, MutationCoordinator mutations) {
        this.spec = Objects.requireNonNull(spec); this.mutations = Objects.requireNonNull(mutations);
    }
    @Override protected long doExecute(ExecutionContext context) { 
        mutations.insert(spec.tableId(), spec.row(), context); 
        return 1; 
    }
    @Override protected void doClose() { }
}
