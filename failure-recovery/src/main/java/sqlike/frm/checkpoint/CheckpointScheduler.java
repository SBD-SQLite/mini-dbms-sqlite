package sqlike.frm.checkpoint;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs periodic checkpoints while allowing explicit lifecycle control. */
public class CheckpointScheduler implements AutoCloseable {

    private static final long STOP_TIMEOUT_SECONDS = 5L;

    private final long intervalMs;
    private final Runnable checkpointTask;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduledTask;

    public CheckpointScheduler(Duration interval, Runnable checkpointTask) {
        Objects.requireNonNull(interval, "interval");
        this.intervalMs = interval.toMillis();
        this.checkpointTask = Objects.requireNonNull(checkpointTask, "checkpointTask");
    }

    /** Starts periodic execution; a non-positive interval leaves the scheduler disabled. */
    public synchronized void start() {
        if (intervalMs <= 0 || isRunning()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "frm-checkpoint-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduledTask = executor.scheduleWithFixedDelay(
                this::runCheckpointSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Stops periodic execution; calling this repeatedly is safe. */
    public synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("checkpoint scheduler did not stop in time");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while stopping checkpoint scheduler", error);
            }
            executor = null;
        }
    }

    public synchronized boolean isRunning() {
        return scheduledTask != null && !scheduledTask.isCancelled() && !scheduledTask.isDone();
    }

    @Override
    public void close() {
        stop();
    }

    private void runCheckpointSafely() {
        try {
            checkpointTask.run();
        } catch (RuntimeException ignored) {
            // MOCK: later milestones should route failures to FRM monitoring/diagnostics.
        }
    }
}
