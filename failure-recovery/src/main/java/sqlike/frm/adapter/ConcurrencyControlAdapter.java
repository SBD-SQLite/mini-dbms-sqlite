package sqlike.frm.adapter;

import java.util.Objects;

import sqlike.contract.IConcurrencyControlManager;
import sqlike.frm.FrmException;
import sqlike.frm.port.CcmPort;
import sqlike.shared.ComponentType;
import sqlike.shared.ErrorCode;

/**
 * Plugs the real CCM into FRM's {@link CcmPort} (DIP, PRD v1.0 §1).
 *
 * <p>{@link #waitUntilIdle(long)} forwards to {@code awaitNoActiveTransactions(timeoutMs)}
 * (PRD §3.4), so the bound is CCM's own: it knows which transactions are active and can stop
 * waiting without a helper thread. A checkpoint therefore never blocks forever, and a timeout
 * comes back as {@code false} instead of an exception.
 */
public class ConcurrencyControlAdapter implements CcmPort {

    private final IConcurrencyControlManager ccm;

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
        try {
            return ccm.awaitNoActiveTransactions(timeoutMs);
        } catch (FrmException error) {
            throw error;
        } catch (RuntimeException error) {
            // Keep the blame on CCM: FR4 turns a false into a cancelled checkpoint, but a broken
            // CCM must not be reported as an FRM I/O problem (PRD §2 ErrorInfo).
            throw new FrmException(ErrorCode.INVALID_STATE, ComponentType.CCM,
                    "CCM awaitNoActiveTransactions failed: " + error, error);
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
}
