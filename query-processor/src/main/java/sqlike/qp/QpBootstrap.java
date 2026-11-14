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
    private final QueryProcessor processor;
    private final QueryServer server;
    private final AtomicReference<SystemState> state;

    /** Recovery through the QP ports (stub runtime). */
    public QpBootstrap(IFailureRecovery recovery, IConcurrencyControl concurrency, QueryServer server) {
        this.recovery = Objects.requireNonNull(recovery, "IFailureRecovery cannot be null in QpBootstrap");
        this.concurrency = Objects.requireNonNull(concurrency, "IConcurrencyControl cannot be null in QpBootstrap");
        this.processor = null;
        this.server = Objects.requireNonNull(server, "QueryServer cannot be null in QpBootstrap");
        this.state = new AtomicReference<>(SystemState.STARTING);
    }

    /** Recovery through QueryProcessor.initialize() (supergroup FRM and CCM). */
    public QpBootstrap(QueryProcessor processor, QueryServer server) {
        this.recovery = null;
        this.concurrency = null;
        this.processor = Objects.requireNonNull(processor, "QueryProcessor cannot be null in QpBootstrap");
        this.server = Objects.requireNonNull(server, "QueryServer cannot be null in QpBootstrap");
        if (server.getProcessor() != processor) throw new IllegalArgumentException("Bootstrap and server must use the same QueryProcessor");
        this.state = new AtomicReference<>(SystemState.STARTING);
    }

    public static QpBootstrap forComponents(sqlike.contract.IQueryOptimizer optimizer,
            sqlike.contract.IStorageManager storage, sqlike.contract.IConcurrencyControlManager concurrency,
            sqlike.contract.IFailureRecoveryManager recovery, QpConfiguration configuration) {
        QueryProcessor processor = new QueryProcessor(optimizer, storage, concurrency, recovery, configuration);
        return new QpBootstrap(processor, new QueryServer(processor, configuration));
    }

    public synchronized void start() {
        if (state.get() == SystemState.READY) {
            return;
        }
        state.set(SystemState.RECOVERING);
        try {
            if (processor != null) {
                processor.initialize();
            } else {
                RecoveryReport report = recovery.recover();
                validateRecovery(report);
                concurrency.initialize(report);
                if (server.getProcessor() instanceof QueryProcessor qp) {
                    qp.markReady();
                }
            }
            server.start();
            state.set(SystemState.READY);
        } catch (RuntimeException | LinkageError e) {
            state.set(SystemState.FAILED);
            throw e;
        }
    }

    public synchronized void stop() {
        RuntimeException failure = null;
        try { server.stop(); } catch (RuntimeException e) { failure = e; }
        try {
            if (server.getProcessor() instanceof QueryProcessor qp) qp.shutdown();
        } catch (RuntimeException e) {
            if (failure == null) failure = e;
            else if (failure != e) failure.addSuppressed(e);
        }
        state.set(failure == null ? SystemState.STOPPED : SystemState.FAILED);
        if (failure != null) throw failure;
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
