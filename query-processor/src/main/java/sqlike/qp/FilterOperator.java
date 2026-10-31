package sqlike.qp;

import java.util.Optional;
import sqlike.shared.Expression;

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

    @Override
    protected void doOpen() {
        child.open(requireContext());
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        while (true) {
            Optional<RowEnvelope> candidate = child.next();
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            RowEnvelope envelope = candidate.get();
            if (evaluator.evaluateCondition(predicate, envelope.row(), child.schema())) {
                return Optional.of(envelope);
            }
        }
    }

    @Override
    protected void doClose() {
        child.close();
    }
}
