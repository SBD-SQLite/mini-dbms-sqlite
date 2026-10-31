package sqlike.qp;

import sqlike.shared.Expression;

/**
 * Specification for filter operator carrying the predicate expression, matching CLASS DIAGRAM FIX.
 */
public record FilterSpec(Expression predicate) {
    public FilterSpec {
        if (predicate == null) {
            throw new IllegalArgumentException("Predicate expression cannot be null in FilterSpec");
        }
    }
}
