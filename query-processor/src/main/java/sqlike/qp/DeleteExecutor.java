package sqlike.qp;

import java.util.List;

public final class DeleteExecutor extends AbstractCommandExecutor {
    private final Operator source;
    private final DeleteSpec spec;
    private final MutationTargetCollector collector;
    private final MutationCoordinator mutations;

    public DeleteExecutor(
            Operator source,
            DeleteSpec spec,
            MutationTargetCollector collector,
            MutationCoordinator mutations) {
        super();
        if (source == null) {
            throw new IllegalArgumentException("Source operator cannot be null in DeleteExecutor");
        }
        if (spec == null) {
            throw new IllegalArgumentException("DeleteSpec cannot be null in DeleteExecutor");
        }
        if (collector == null) {
            throw new IllegalArgumentException("MutationTargetCollector cannot be null in DeleteExecutor");
        }
        if (mutations == null) {
            throw new IllegalArgumentException("MutationCoordinator cannot be null in DeleteExecutor");
        }
        this.source = source;
        this.spec = spec;
        this.collector = collector;
        this.mutations = mutations;
    }

    @Override
    protected long doExecute(ExecutionContext context) {
        List<StoredRecord> targets = collector.collect(source, spec.tableName(), context);
        long deletedCount = 0;
        for (StoredRecord before : targets) {
            mutations.delete(spec.tableName(), before, context);
            deletedCount++;
        }
        return deletedCount;
    }

    @Override
    protected void doClose() {
        source.close();
    }
}
