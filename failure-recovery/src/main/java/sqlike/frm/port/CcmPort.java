package sqlike.frm.port;

/**
 * What FRM needs from CCM to run checkpoint, historical recovery, and startup recovery.
 *
 * <p>Maps to {@code pauseNewTransactions}, {@code awaitNoActiveTransactions},
 * {@code resumeTransactions}, and {@code initAfterRecovery} of the CCM contract.
 */
public interface CcmPort {

    /** Holds new transactions; active ones keep running until they finish (FR-06). */
    void pauseNewTransactions();

    /** Waits until no transaction is active; returns false if {@code timeoutMs} passes first. */
    boolean waitUntilIdle(long timeoutMs);

    /** Lets new transactions start again. */
    void resumeTransactions();

    /** Clears CCM state and continues txIds above {@code maxTxId} after recovery (CC-05). */
    void initAfterRecovery(long maxTxId);
}
