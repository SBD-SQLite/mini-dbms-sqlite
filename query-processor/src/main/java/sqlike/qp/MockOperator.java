package sqlike.qp;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import sqlike.shared.PlanNodeKind;

/**
 * Mock implementation of Operator returned by PlanTranslator during Milestone 1.
 * Records operator kind, child operators, and output schema.
 */
public class MockOperator implements Operator {
    private final PlanNodeKind kind;
    private final Schema schema;
    private final List<Operator> children;
    private boolean opened = false;
    private boolean closed = false;

    public MockOperator(PlanNodeKind kind, Schema schema, List<Operator> children) {
        this.kind = kind;
        this.schema = schema != null ? schema : Schema.of(List.of());
        this.children = children != null ? List.copyOf(children) : List.of();
    }

    public PlanNodeKind getKind() {
        return kind;
    }

    public List<Operator> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public boolean isOpened() {
        return opened;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public void open(ExecutionContext context) {
        // TODO(M1-MOCK): Open mock operator and its children
        this.opened = true;
        for (Operator child : children) {
            child.open(context);
        }
    }

    @Override
    public Optional<RowEnvelope> next() {
        // TODO(M1-MOCK): Return empty iterator for Milestone 1 mock
        return Optional.empty();
    }

    @Override
    public void close() {
        // TODO(M1-MOCK): Close mock operator and its children
        this.closed = true;
        for (Operator child : children) {
            child.close();
        }
    }
}
