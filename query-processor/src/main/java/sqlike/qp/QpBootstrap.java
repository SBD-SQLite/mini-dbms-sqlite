package sqlike.qp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import sqlike.qp.port.QpContracts.IConcurrencyControl;
import sqlike.qp.port.QpContracts.IFailureRecovery;
import sqlike.qp.port.QpContracts.RecoveryReport;

/**
 * Bootstrap coordinator for Query Processor startup, recovery validation, and server lifecycle
 * matching CLASS DIAGRAM FIX (Tab 4).
 */
public final class QpBootstrap {
    private final IFailureRecovery recovery;
    private final IConcurrencyControl concurrency;
    private final QueryServer server;
    private final AtomicReference<SystemState> state;

    public QpBootstrap(IFailureRecovery recovery, IConcurrencyControl concurrency, QueryServer server) {
        this.recovery = Objects.requireNonNull(recovery, "IFailureRecovery cannot be null in QpBootstrap");
        this.concurrency = Objects.requireNonNull(concurrency, "IConcurrencyControl cannot be null in QpBootstrap");
        this.server = Objects.requireNonNull(server, "QueryServer cannot be null in QpBootstrap");
        this.state = new AtomicReference<>(SystemState.STARTING);
    }

    public void start() {
        if (state.get() == SystemState.READY) {
            return;
        }
        state.set(SystemState.RECOVERING);
        try {
            RecoveryReport report = recovery.recover();
            validateRecovery(report);
            concurrency.initialize(report);
            if (server.getProcessor() instanceof QueryProcessor qp) {
                qp.markReady();
            }
            server.start();
            state.set(SystemState.READY);
        } catch (RuntimeException e) {
            state.set(SystemState.FAILED);
            throw e;
        }
    }

    public void stop() {
        try {
            server.stop();
            if (server.getProcessor() instanceof QueryProcessor qp) {
                qp.shutdown();
            }
        } finally {
            state.set(SystemState.STOPPED);
        }
    }

    public SystemState getState() {
        return state.get();
    }

    private void validateRecovery(RecoveryReport report) {
        if (report == null || !report.success()) {
            throw new IllegalStateException("Startup recovery failed");
        }
    }
}

