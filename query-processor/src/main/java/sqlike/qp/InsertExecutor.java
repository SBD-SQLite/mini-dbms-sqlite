package sqlike.qp;

import java.util.Objects;
import sqlike.qp.port.QpContracts.AccessMode;

public final class InsertExecutor extends AbstractCommandExecutor {
    private final InsertSpec spec;
    private final MutationCoordinator mutations;

    public InsertExecutor(InsertSpec spec, MutationCoordinator mutations) {
        super();
        this.spec = Objects.requireNonNull(spec);
        this.mutations = Objects.requireNonNull(mutations);
    }

    public InsertSpec getSpec() {
        return spec;
    }

    public MutationCoordinator getMutations() {
        return mutations;
    }

    @Override
    protected long doExecute(ExecutionContext context) {
        context.requireGrant(spec.tableName(), AccessMode.WRITE);
        mutations.insert(spec.tableName(), spec.row(), context);
        return 1;
    }

    @Override
    protected void doClose() { }
}
