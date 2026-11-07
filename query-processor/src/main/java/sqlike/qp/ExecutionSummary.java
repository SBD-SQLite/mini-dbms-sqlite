package sqlike.qp;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sqlike.qp.port.QpContracts.DbmsError;
import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.QueryResult;
import sqlike.shared.ResultKind;

/**
 * Summary of statement execution produced at the end of a query or command.
 */
public final class ExecutionSummary {
    private final boolean success;
    private final long affectedRows;
    private final String message;
    private final Optional<DbmsError> error;
    private final Optional<ErrorInfo> errorInfo;

    public ExecutionSummary(boolean success, long affectedRows, String message, Optional<DbmsError> error) {
        this(success, affectedRows, message, error, Optional.empty());
    }

    public ExecutionSummary(
            boolean success,
            long affectedRows,
            String message,
            Optional<DbmsError> error,
            Optional<ErrorInfo> errorInfo) {
        this.success = success;
        this.affectedRows = affectedRows;
        this.message = message == null ? "" : message;
        this.error = error == null ? Optional.empty() : error;
        this.errorInfo = errorInfo == null ? Optional.empty() : errorInfo;
    }

    public static ExecutionSummary ok(long affectedRows, String message) {
        return new ExecutionSummary(true, affectedRows, message, Optional.empty());
    }

    public static ExecutionSummary failure(DbmsError error) {
        String msg = error != null && error.message() != null ? error.message() : "Execution failed";
        return new ExecutionSummary(false, 0L, msg, Optional.ofNullable(error));
    }

    public static ExecutionSummary failure(ErrorInfo info) {
        if (info == null) {
            return failure((DbmsError) null);
        }
        return new ExecutionSummary(
                false, 0L, info.message(), Optional.of(new DbmsError(info.message())), Optional.of(info));
    }

    public static ExecutionSummary fromQueryResult(QueryResult result) {
        if (result == null) {
            return failure(new DbmsError("Null QueryResult"));
        }
        if (result.kind() == ResultKind.ERROR) {
            return result.error()
                    .map(ExecutionSummary::failure)
                    .orElseGet(() -> failure(new DbmsError(result.message())));
        }
        long count = result.kind() == ResultKind.ROWS ? result.rows().size() : result.affectedRows();
        return ok(count, result.message());
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean success() {
        return success;
    }

    public long getAffectedRows() {
        return affectedRows;
    }

    public long affectedRows() {
        return affectedRows;
    }

    public String getMessage() {
        return message;
    }

    public String message() {
        return message;
    }

    public Optional<DbmsError> getError() {
        return error;
    }

    public Optional<DbmsError> error() {
        return error;
    }

    public Optional<ErrorInfo> getErrorInfo() {
        return errorInfo;
    }

    public QueryResult toQueryResult() {
        if (!success) {
            String errMsg = error.map(DbmsError::message).orElse(message);
            ErrorInfo info = errorInfo.orElseGet(
                    () -> new ErrorInfo(ErrorCode.INVALID_STATE, ComponentType.QP, errMsg, Optional.empty()));
            return new QueryResult(ResultKind.ERROR, List.of(), List.of(), 0L, info.message(), Optional.of(info));
        }
        return new QueryResult(ResultKind.AFFECTED_ROWS, List.of(), List.of(), affectedRows, message, Optional.empty());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof ExecutionSummary that) {
            return success == that.success
                    && affectedRows == that.affectedRows
                    && Objects.equals(message, that.message)
                    && Objects.equals(error, that.error)
                    && Objects.equals(errorInfo, that.errorInfo);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, affectedRows, message, error, errorInfo);
    }

    @Override
    public String toString() {
        return "ExecutionSummary[success=" + success
                + ", affectedRows=" + affectedRows
                + ", message=" + message
                + ", error=" + error
                + ", errorInfo=" + errorInfo + "]";
    }
}
