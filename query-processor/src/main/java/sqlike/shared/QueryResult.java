package sqlike.shared;

import java.util.List;
import java.util.Optional;

public record QueryResult(
        ResultKind kind,
        List<ResultColumn> columns,
        List<List<Value>> rows,
        long affectedRows,
        Optional<TransactionResponse> transaction,
        String message,
        Optional<ErrorInfo> error) {
    public QueryResult(ResultKind kind, List<ResultColumn> columns, List<List<Value>> rows,
            long affectedRows, String message, Optional<ErrorInfo> error) {
        this(kind, columns, rows, affectedRows, Optional.empty(), message, error);
    }

    public QueryResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        transaction = transaction == null ? Optional.empty() : transaction;
        error = error == null ? Optional.empty() : error;
    }
}
