package sqlike.qp;

import java.util.Optional;
import sqlike.shared.ErrorCode;

public abstract class AbstractOperator implements Operator {
    private final Schema outputSchema;
    private OperatorState state;
    protected ExecutionContext context;

    protected AbstractOperator(Schema outputSchema) {
        if (outputSchema == null) {
            throw new IllegalArgumentException("Output schema cannot be null in AbstractOperator");
        }
        this.outputSchema = outputSchema;
        this.state = OperatorState.NEW;
    }

    @Override
    public final Schema schema() {
        return outputSchema;
    }

    public OperatorState getState() {
        return state;
    }

    @Override
    public final void open(ExecutionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("ExecutionContext cannot be null when opening operator");
        }
        if (state == OperatorState.OPEN) {
            throw new QpException(ErrorCode.INVALID_STATE, "Operator is already OPEN");
        }
        if (state == OperatorState.FAILED) {
            throw new QpException(ErrorCode.INVALID_STATE, "Cannot reopen operator in FAILED state without closing first");
        }
        this.context = context;
        try {
            doOpen();
            this.state = OperatorState.OPEN;
        } catch (RuntimeException e) {
            this.state = OperatorState.FAILED;
            throw e;
        }
    }

    @Override
    public final Optional<RowEnvelope> next() {
        if (state == OperatorState.EXHAUSTED) {
            return Optional.empty();
        }
        requireOpen();
        try {
            Optional<RowEnvelope> nextRow = doNext();
            if (nextRow.isEmpty()) {
                this.state = OperatorState.EXHAUSTED;
            }
            return nextRow;
        } catch (RuntimeException e) {
            this.state = OperatorState.FAILED;
            throw e;
        }
    }

    @Override
    public final void close() {
        try {
            doClose();
        } finally {
            this.context = null;
            this.state = OperatorState.CLOSED;
        }
    }

    protected abstract void doOpen();

    protected abstract Optional<RowEnvelope> doNext();

    protected abstract void doClose();

    protected ExecutionContext requireContext() {
        if (context == null) {
            throw new QpException(ErrorCode.INVALID_STATE, "Operator ExecutionContext is not initialized");
        }
        return context;
    }

    private void requireOpen() {
        if (state != OperatorState.OPEN) {
            throw new QpException(
                    ErrorCode.INVALID_STATE,
                    "Operator must be in OPEN state to call next(), current state: " + state);
        }
    }
}
