package sqlike.frm.mock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sqlike.frm.port.CcmPort;

/**
 * Stand-in for CCM so checkpoint and recovery can be tested without the real CCM (FR-09).
 *
 * <p>Scenarios mark transactions active or finished by hand. The mock follows the CCM rules FRM
 * relies on: {@code pauseNewTransactions} blocks only new transactions, active ones may still
 * finish, and {@code initAfterRecovery} clears state and continues IDs above the given value.
 * Every call is recorded so a scenario can check that FRM always resumed the gate.
 */
public class MockCCM implements CcmPort {

    private boolean paused = false;
    private final Set<Long> activeTx = new HashSet<>();
    private long maxTxIdAfterRecovery = 0;
    private int initAfterRecoveryCalls = 0;
    private final List<String> calls = new ArrayList<>();

    @Override
    public synchronized void pauseNewTransactions() {
        paused = true;
        calls.add("pause");
    }

    @Override
    public synchronized boolean waitUntilIdle(long timeoutMs) {
        calls.add("waitUntilIdle");
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
        calls.add("resume");
    }

    @Override
    public synchronized void initAfterRecovery(long maxTxId) {
        if (maxTxId < 0) {
            throw new IllegalArgumentException("maxTxId must not be negative");
        }
        activeTx.clear();
        paused = false;
        maxTxIdAfterRecovery = maxTxId;
        initAfterRecoveryCalls++;
        calls.add("initAfterRecovery(" + maxTxId + ")");
        notifyAll();
    }

    /** Marks a transaction as running; rejected while new transactions are paused. */
    public synchronized void simulateActive(long txId) {
        if (paused) {
            throw new IllegalStateException("new transactions are paused");
        }
        if (txId <= maxTxIdAfterRecovery) {
            throw new IllegalStateException(
                    "txId " + txId + " reuses an ID at or below " + maxTxIdAfterRecovery);
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

    public synchronized boolean hasActive() {
        return !activeTx.isEmpty();
    }

    public synchronized long getMaxTxIdAfterRecovery() {
        return maxTxIdAfterRecovery;
    }

    public synchronized int getInitAfterRecoveryCalls() {
        return initAfterRecoveryCalls;
    }

    /** Every CcmPort call in order, e.g. {@code [pause, waitUntilIdle, resume]}. */
    public synchronized List<String> getCalls() {
        return List.copyOf(calls);
    }
}
