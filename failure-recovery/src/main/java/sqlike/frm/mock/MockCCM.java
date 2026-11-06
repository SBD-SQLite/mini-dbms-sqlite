package sqlike.frm.mock;

import java.util.HashSet;
import java.util.Set;

import sqlike.frm.port.CcmPort;

/**
 * Stand-in for CCM so checkpoint and recovery can be tested without the real CCM (FR-09).
 *
 * <p>Scenarios mark transactions active or finished by hand to drive checkpoint waiting.
 */
public class MockCCM implements CcmPort {

    private boolean paused = false;
    private final Set<Long> activeTx = new HashSet<>();
    private long maxTxIdAfterRecovery = 0;

    @Override
    public synchronized void pauseNewTransactions() {
        paused = true;
    }

    @Override
    public synchronized boolean waitUntilIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!activeTx.isEmpty()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void resumeTransactions() {
        paused = false;
    }

    @Override
    public synchronized void initAfterRecovery(long maxTxId) {
        activeTx.clear();
        paused = false;
        maxTxIdAfterRecovery = maxTxId;
    }

    /** Marks a transaction as running; rejected while new transactions are paused. */
    public synchronized void simulateActive(long txId) {
        if (paused) {
            throw new IllegalStateException("new transactions are paused");
        }
        activeTx.add(txId);
    }

    /** Marks a transaction as committed or aborted and wakes a waiting checkpoint. */
    public synchronized void simulateFinish(long txId) {
        activeTx.remove(txId);
        notifyAll();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized long getMaxTxIdAfterRecovery() {
        return maxTxIdAfterRecovery;
    }
}
