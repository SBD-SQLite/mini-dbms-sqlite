package sqlike.frm.port;

/**
 * What the facade needs from FR3 to undo one transaction during normal operation (FR-04).
 *
 * <p>FR3's {@code RollbackManager} implements this once it lives in a named package. Contract
 * (PRD v1.0 §11): undo the transaction's changes in reverse LSN order through SM, return only
 * after every undo succeeded, and do <b>not</b> write the final ABORT record; CCM writes it via
 * {@code logAbort}. A transaction that already finished its abort is left unchanged. Committed
 * or unknown transactions are rejected with a clear error.
 */
@FunctionalInterface
public interface RollbackPort {

    void rollback(long txId);
}
