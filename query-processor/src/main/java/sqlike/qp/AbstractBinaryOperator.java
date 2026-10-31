package sqlike.qp;
import java.util.Objects;
import sqlike.shared.ErrorCode;

public abstract class AbstractBinaryOperator extends AbstractOperator {
    private boolean leftOpened;
    private boolean rightOpened;
    protected final Operator left;
    protected final Operator right;

    protected AbstractBinaryOperator(Operator left, Operator right, Schema outputSchema) {
        super(outputSchema);
        this.left = Objects.requireNonNull(left);
        this.right = Objects.requireNonNull(right);
        if (left == right) {
            throw new IllegalArgumentException("Join children must have separate iterator state");
        }
    }

    @Override
    protected void doOpen() {
        if (leftOpened || rightOpened) {
            throw new QpException(ErrorCode.INVALID_STATE, "Close join children before reopening");
        }
        try {
            leftOpened = true;
            left.open(requireContext());
            rightOpened = true;
            right.open(requireContext());
        } catch (RuntimeException | Error error) {
            try {
                doClose();
            } catch (RuntimeException | Error cleanupError) {
                if (cleanupError != error) {
                    error.addSuppressed(cleanupError);
                }
            }
            throw error;
        }
    }

    @Override
    protected void doClose() {
        try {
            if (leftOpened) {
                leftOpened = false;
                left.close();
            }
        } catch (RuntimeException | Error error) {
            try {
                closeRight();
            } catch (RuntimeException | Error cleanupError) {
                if (cleanupError != error) {
                    error.addSuppressed(cleanupError);
                }
            }
            throw error;
        }
        closeRight();
    }

    private void closeRight() {
        if (rightOpened) {
            rightOpened = false;
            right.close();
        }
    }
}
