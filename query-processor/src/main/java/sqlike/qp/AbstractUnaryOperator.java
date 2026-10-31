package sqlike.qp;

public abstract class AbstractUnaryOperator extends AbstractOperator {
    protected final Operator child;

    protected AbstractUnaryOperator(Operator child, Schema outputSchema) {
        super(outputSchema);
        if (child == null) {
            throw new IllegalArgumentException("Child operator cannot be null in AbstractUnaryOperator");
        }
        this.child = child;
    }
}
