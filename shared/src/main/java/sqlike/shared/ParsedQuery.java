package sqlike.shared;

import java.util.Optional;

public record ParsedQuery(
        StatementType statementType,
        Optional<PlanNode> root,
        Optional<TransactionCommand> transactionCommand
) {
}
