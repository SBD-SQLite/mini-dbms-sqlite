package sqlike.qp;

/**
 * Interface for non-query commands (INSERT, UPDATE, DELETE, CREATE INDEX) returning affected row counts.
 */
public interface CommandExecutor extends AutoCloseable {
    long execute(ExecutionContext context);

    @Override
    void close();
}
