package sqlike.qp;

import java.util.List;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.shared.ErrorCode;

/**
 * Physical DELETE command executor (QP3 — Milestone 2 / PRD §10).
 * Verifies table WRITE permission, eagerly materializes all target before-images via
 * MutationTargetCollector, and delegates logged deletion per RID to MutationCoordinator.
 */
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

    public Operator getSource() {
        return source;
    }

    public DeleteSpec getSpec() {
        return spec;
    }

    @Override
    protected long doExecute(ExecutionContext context) {
        requireWriteGrant(context, spec.tableName());
        List<StoredRecord> targets = collector.collect(source, spec.tableName(), context);
        long deletedCount = 0;
        for (StoredRecord before : targets) {
            checkContextActive(context);
            mutations.delete(spec.tableName(), before, context);
            deletedCount++;
        }
        return deletedCount;
    }

    @Override
    protected void doClose() {
        source.close();
    }

    private void requireWriteGrant(ExecutionContext context, String tableName) {
        try {
            context.requireGrant(tableName, AccessMode.WRITE);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Missing WRITE grant for table " + tableName;
            if (msg.contains("cancelled") || msg.contains("aborted")) {
                throw new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
            }
            throw new QpException(ErrorCode.INVALID_STATE, msg);
        }
    }

    private void checkContextActive(ExecutionContext context) {
        try {
            context.check();
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Transaction cancelled in DeleteExecutor";
            throw new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
        }
    }
}
