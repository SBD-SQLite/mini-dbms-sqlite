package sqlike.qp;

import java.util.Optional;
import sqlike.shared.ErrorCode;
import sqlike.shared.Expression;

/**
 * Unary row operator evaluating boolean filter predicates (QP3 — Milestone 2 / PRD §6).
 */
public final class FilterOperator extends AbstractUnaryOperator {
    private final Expression predicate;
    private final IExpressionEvaluator evaluator;

    public FilterOperator(
            Operator child,
            FilterSpec spec,
            IExpressionEvaluator evaluator,
            Schema schema) {
        super(child, schema);
        if (spec == null) {
            throw new IllegalArgumentException("FilterSpec cannot be null in FilterOperator");
        }
        if (evaluator == null) {
            throw new IllegalArgumentException("IExpressionEvaluator cannot be null in FilterOperator");
        }
        this.predicate = spec.predicate();
        this.evaluator = evaluator;
    }

    public FilterOperator(
            Operator child,
            FilterSpec spec,
            IExpressionEvaluator evaluator) {
        this(child, spec, evaluator, child != null ? child.schema() : null);
    }

    public Expression getPredicate() {
        return predicate;
    }

    public Operator getChild() {
        return child;
    }

    @Override
    protected void doOpen() {
        ExecutionContext ctx = requireContext();
        checkContextActive(ctx);
        child.open(ctx);
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        ExecutionContext ctx = requireContext();
        while (true) {
            checkContextActive(ctx);
            Optional<RowEnvelope> candidate = child.next();
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            RowEnvelope envelope = candidate.get();
            boolean matched = evaluator instanceof ExpressionEvaluator ee
                    ? ee.evaluateConditionWithContext(predicate, envelope.row(), child.schema(), ctx)
                    : evaluator.evaluateCondition(predicate, envelope.row(), child.schema());
            if (matched) {
                return Optional.of(envelope);
            }
        }
    }

    @Override
    protected void doClose() {
        child.close();
    }

    private void checkContextActive(ExecutionContext ctx) {
        try {
            ctx.check();
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Transaction cancelled in FilterOperator";
            throw new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
        }
    }
}
