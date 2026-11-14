package sqlike.qp;

import java.util.Optional;
import sqlike.shared.ErrorCode;
import sqlike.shared.LimitSpec;

/**
 * Physical operator for LIMIT <count> (QP-16* / PRD §13).
 * Preserves output schema even when count is 0 and closes the child operator immediately
 * once the row limit is reached.
 */
public final class LimitOperator extends AbstractUnaryOperator {
    private final LimitSpec spec;
    private long remaining;
    private boolean childClosed;

    public LimitOperator(Operator child, LimitSpec spec, Schema schema) {
        super(child, schema);
        if (spec == null) {
            throw new IllegalArgumentException("LimitSpec cannot be null in LimitOperator");
        }
        if (spec.count() < 0) {
            throw new IllegalArgumentException("LIMIT count cannot be negative: " + spec.count());
        }
        this.spec = spec;
    }

    public LimitOperator(Operator child, LimitSpec spec) {
        this(child, spec, child != null ? child.schema() : Schema.of(java.util.List.of()));
    }

    public LimitSpec getSpec() {
        return spec;
    }

    @Override
    protected void doOpen() {
        ExecutionContext ctx = requireContext();
        checkContextActive(ctx);
        this.remaining = spec.count();
        this.childClosed = false;
        child.open(ctx);
        if (this.remaining == 0) {
            closeChildOnce();
        }
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        checkContextActive(requireContext());
        if (remaining <= 0) {
            closeChildOnce();
            return Optional.empty();
        }
        Optional<RowEnvelope> next;
        try {
            next = child.next();
        } catch (RuntimeException e) {
            closeChildOnce();
            throw e;
        }
        if (next.isEmpty()) {
            closeChildOnce();
            return Optional.empty();
        }
        remaining--;
        if (remaining == 0) {
            closeChildOnce();
        }
        return next;
    }

    @Override
    protected void doClose() {
        closeChildOnce();
    }

    private void closeChildOnce() {
        if (!childClosed) {
            childClosed = true;
            child.close();
        }
    }

    private void checkContextActive(ExecutionContext ctx) {
        try {
            ctx.check();
        } catch (IllegalStateException e) {
            closeChildOnce();
            String msg = e.getMessage() != null ? e.getMessage() : "Transaction cancelled in LimitOperator";
            throw new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
        }
    }
}
