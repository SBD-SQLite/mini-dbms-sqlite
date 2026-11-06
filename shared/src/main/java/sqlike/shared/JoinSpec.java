package sqlike.shared;

import java.util.List;
import java.util.Optional;

public record JoinSpec(
        JoinType joinType,
        Optional<Expression> condition,
        JoinAlgorithm algorithm,
        List<String> commonColumns
) {
}
