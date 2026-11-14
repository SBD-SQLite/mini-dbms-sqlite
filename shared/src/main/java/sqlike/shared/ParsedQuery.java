package sqlike.shared;

import java.util.Optional;

public record ParsedQuery(
        StatementType statementType,
        Optional<PlanNode> root,
        Optional<TransactionCommand> transactionCommand) {
    public ParsedQuery {
        root = root == null ? Optional.empty() : root;
        transactionCommand = transactionCommand == null ? Optional.empty() : transactionCommand;
    }
}
