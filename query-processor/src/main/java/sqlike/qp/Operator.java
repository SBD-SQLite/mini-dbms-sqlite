package sqlike.qp;

import java.util.Optional;

/**
 * Core query iterator interface (Volcano / Iterator model) producing row envelopes.
 */
public interface Operator extends AutoCloseable {
    Schema schema();

    void open(ExecutionContext context);

    Optional<RowEnvelope> next();

    @Override
    void close();
}
