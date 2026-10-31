package sqlike.qp;

import sqlike.shared.ErrorCode;

public abstract class AbstractCommandExecutor implements CommandExecutor {
    private boolean executed;
    private boolean closed;

    protected AbstractCommandExecutor() {
        this.executed = false;
        this.closed = false;
    }

    @Override
    public final long execute(ExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("ExecutionContext cannot be null when executing command");
        }
        requireNotExecuted();
        this.executed = true;
        return doExecute(context);
    }

    @Override
    public final void close() {
        if (!closed) {
            try {
                doClose();
            } finally {
                this.closed = true;
            }
        }
    }

    protected abstract long doExecute(ExecutionContext context);

    protected abstract void doClose();

    private void requireNotExecuted() {
        if (closed) {
            throw new QpException(ErrorCode.INVALID_STATE, "CommandExecutor is already closed");
        }
        if (executed) {
            throw new QpException(ErrorCode.INVALID_STATE, "CommandExecutor is one-shot and cannot be re-executed");
        }
    }
}
