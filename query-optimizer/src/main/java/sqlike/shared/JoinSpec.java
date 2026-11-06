package sqlike.shared;

import java.util.List;
import java.util.Optional;

public record JoinSpec(JoinType joinType, Optional<Expression> condition, JoinAlgorithm algorithm,
                       List<String> commonColumns) {
    public JoinSpec {
        condition = condition == null ? Optional.empty() : condition;
        commonColumns = commonColumns == null ? List.of() : List.copyOf(commonColumns);
    }
}
