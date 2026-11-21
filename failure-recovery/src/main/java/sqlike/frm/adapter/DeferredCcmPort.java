package sqlike.frm.adapter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import sqlike.contract.IConcurrencyControlManager;
import sqlike.frm.FrmException;
import sqlike.frm.port.CcmPort;
import sqlike.shared.ErrorCode;

/**
 * {@link CcmPort} for the startup wiring in which FRM and CCM need each other (PRD v1.0 §14).
 *
 * <p>{@code FailureRecoveryManager} takes a {@code CcmPort} in its constructor, and
 * {@code ConcurrencyControlManager} takes the FRM facade in its constructor, so one of the two
 * has to be connected afterwards. The agreed order is:
 *
 * <ol>
 *   <li>Build SM, then {@code port = new DeferredCcmPort()} and
 *       {@code frm = new FailureRecoveryManager(sm, port, ...)}; give SM {@code frm} as its WAL gate.</li>
 *   <li>Build CCM with {@code frm}, then {@code port.bind(ccm)}.</li>
 *   <li>{@code result = frm.recover()}. Until it returns, FRM rejects every logging call with
 *       {@code INVALID_STATE} (FR-07). The first startup already pauses CCM here for the
 *       baseline checkpoint, which is why {@code bind} must come first.</li>
 *   <li>QP calls {@code ccm.initAfterRecovery(result.lastTxId())}, then opens the server.
 *       {@code recover()} does not call it itself: PRD §14 gives that call to QP, and FRM makes
 *       it only after point-in-time recovery.</li>
 * </ol>
 *
 * <p>Every call made before {@link #bind} fails with {@code INVALID_STATE}, so a wrong order shows
 * up as an error. The port never reports an unconnected CCM as idle.
 */
public final class DeferredCcmPort implements CcmPort {

    private final AtomicReference<CcmPort> target = new AtomicReference<>();

    /** Connects the real CCM through {@link ConcurrencyControlAdapter}; allowed once. */
    public void bind(IConcurrencyControlManager ccm) {
        bind(new ConcurrencyControlAdapter(ccm));
    }

    /** Connects any {@link CcmPort}, e.g. a test double; allowed once. */
    public void bind(CcmPort port) {
        Objects.requireNonNull(port, "port");
        if (port == this) {
            throw new IllegalArgumentException("a DeferredCcmPort cannot be bound to itself");
        }
        if (!target.compareAndSet(null, port)) {
            throw new FrmException(ErrorCode.INVALID_STATE, "CCM is already bound to this FRM");
        }
    }

    public boolean isBound() {
        return target.get() != null;
    }

    @Override
    public void pauseNewTransactions() {
        bound("pauseNewTransactions").pauseNewTransactions();
    }

    @Override
    public boolean waitUntilIdle(long timeoutMs) {
        return bound("waitUntilIdle").waitUntilIdle(timeoutMs);
    }

    @Override
    public void resumeTransactions() {
        bound("resumeTransactions").resumeTransactions();
    }

    @Override
    public void initAfterRecovery(long maxTxId) {
        bound("initAfterRecovery").initAfterRecovery(maxTxId);
    }

    private CcmPort bound(String operation) {
        CcmPort port = target.get();
        if (port == null) {
            throw new FrmException(ErrorCode.INVALID_STATE, operation + " rejected: CCM is not bound yet; "
                    + "call DeferredCcmPort.bind(ccm) before FailureRecoveryManager.recover()");
        }
        return port;
    }
}
