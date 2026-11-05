package sqlike.shared;

import java.util.Optional;

public record IndexCondition(
        IndexOperator operator,
        Value key,
        Optional<Value> upperBound,
        boolean lowerInclusive,
        boolean upperInclusive
) {
}
