package sqlike.frm.adapter;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import sqlike.contract.IConcurrencyControlManager;
import sqlike.frm.FrmException;
import sqlike.frm.port.CcmPort;
import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;

/**
 * Plugs the real CCM into FRM's {@link CcmPort} (DIP, PRD v1.0 §1).
 *
 * <p>PRD §3.4 adds {@code awaitNoActiveTransactions(long timeoutMs)} to CCM. Until that overload
 * is in the contract, {@link #waitUntilIdle(long)} runs the old blocking
 * {@code awaitNoActiveTransactions()} on a helper thread and stops waiting after the timeout, so a
 * checkpoint never blocks forever. Replace the body with the overload once it exists.
 */
public class ConcurrencyControlAdapter implements CcmPort, AutoCloseable {

    private final IConcurrencyControlManager ccm;
    private final ExecutorService waiter = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "frm-ccm-idle-wait");
        thread.setDaemon(true);
        return thread;
    });

    public ConcurrencyControlAdapter(IConcurrencyControlManager ccm) {
        this.ccm = Objects.requireNonNull(ccm, "ccm");
    }

    @Override
    public void pauseNewTransactions() {
        ccm.pauseNewTransactions();
    }

    @Override
    public boolean waitUntilIdle(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        // TODO(contract): return ccm.awaitNoActiveTransactions(timeoutMs) once PRD §3.4 lands.
        Future<?> idle = waiter.submit(ccm::awaitNoActiveTransactions);
        try {
            idle.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException timedOut) {
            idle.cancel(true);
            return false;
        } catch (InterruptedException interrupted) {
            idle.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failed) {
            throw new FrmException(ErrorCode.INVALID_STATE, ComponentType.CCM,
                    "CCM awaitNoActiveTransactions failed: " + failed.getCause(), failed.getCause());
        }
    }

    @Override
    public void resumeTransactions() {
        ccm.resumeTransactions();
    }

    @Override
    public void initAfterRecovery(long maxTxId) {
        ccm.initAfterRecovery(maxTxId);
    }

    @Override
    public void close() {
        waiter.shutdownNow();
    }
}
