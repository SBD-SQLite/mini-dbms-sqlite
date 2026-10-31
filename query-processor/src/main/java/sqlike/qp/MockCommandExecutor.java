package sqlike.qp;

import sqlike.shared.PlanNodeKind;

/**
 * Mock implementation of CommandExecutor returned by PlanTranslator during Milestone 1.
 * Records command kind and returns placeholder affected row count.
 */
public class MockCommandExecutor implements CommandExecutor {
    private final PlanNodeKind kind;
    private boolean executed = false;
    private boolean closed = false;

    public MockCommandExecutor(PlanNodeKind kind) {
        this.kind = kind;
    }

    public PlanNodeKind getKind() {
        return kind;
    }

    public boolean isExecuted() {
        return executed;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public long execute(ExecutionContext context) {
        // TODO(M1-MOCK): Return 0 affected rows placeholder for Milestone 1 mock
        this.executed = true;
        return 0L;
    }

    @Override
    public void close() {
        // TODO(M1-MOCK): Mark executor as closed
        this.closed = true;
    }
}
