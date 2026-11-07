package sqlike.frm.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import sqlike.shared.RecoveryResult;

/**
 * Simulates a crash with a real process kill (FR-08). A crash inside the driver's JVM would let
 * shutdown hooks and open buffers survive, so the workload runs in a child JVM that is killed with
 * {@link Process#destroyForcibly()} once it prints the crash marker. Recovery then runs in another
 * new JVM on the same data directory.
 */
public class CrashSimulator {

    static final String RECOVERY_RESULT = "RECOVERY-RESULT";
    static final String RECOVERY_FAILED = "RECOVERY-FAILED";
    static final String CHILD_ERROR = "CHILD-ERROR";
    static final String NO_CRASH = "SCENARIO-ENDED-WITHOUT-CRASH";

    private final Duration timeout;

    public CrashSimulator(Duration timeout) {
        this.timeout = timeout;
    }

    /** Output of a workload process that was expected to reach its crash point. */
    public record CrashRun(boolean reachedCrashPoint, Optional<String> crashPoint,
            List<String> failedChecks, List<String> output) {
    }

    /** Output of a recovery process. */
    public record RecoveryRun(boolean succeeded, Optional<RecoveryResult> result, List<String> output) {
    }

    /** Runs {@code scenario} in a child JVM until its crash point, then kills it. */
    public CrashRun runUntilCrash(TestScenario scenario, Path dataDir, Duration idleTimeout) {
        Child child = start("run", scenario.name(), dataDir.toString(), String.valueOf(idleTimeout.toMillis()));
        boolean reached = child.awaitMarker(timeout);
        child.kill();
        List<String> output = child.output();
        Optional<String> point = Optional.empty();
        List<String> checks = new ArrayList<>();
        for (String line : output) {
            if (line.startsWith(ScenarioContext.CRASH_MARKER + "\t")) {
                point = Optional.of(line.substring(ScenarioContext.CRASH_MARKER.length() + 1));
            } else if (line.startsWith(ScenarioContext.CHECK_FAILED + "\t")) {
                checks.add(line.substring(ScenarioContext.CHECK_FAILED.length() + 1));
            }
        }
        return new CrashRun(reached, point, checks, output);
    }

    /** Starts a fresh JVM on {@code dataDir} that only runs startup recovery, then exits. */
    public RecoveryRun restartAndRecover(Path dataDir, Duration idleTimeout) {
        Child child = start("recover", dataDir.toString(), String.valueOf(idleTimeout.toMillis()));
        boolean finished = child.awaitExit(timeout);
        if (!finished) {
            child.kill();
        }
        List<String> output = child.output();
        for (String line : output) {
            if (line.startsWith(RECOVERY_RESULT + "\t")) {
                String[] parts = line.split("\t");
                Optional<Long> checkpoint = parts[4].equals("-") ? Optional.empty() : Optional.of(Long.parseLong(parts[4]));
                RecoveryResult result = new RecoveryResult(
                        Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]), checkpoint);
                return new RecoveryRun(finished && child.exitCode() == 0, Optional.of(result), output);
            }
        }
        return new RecoveryRun(false, Optional.empty(), output);
    }

    private Child start(String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (Map.Entry<Object, Object> property : System.getProperties().entrySet()) {
            String key = property.getKey().toString();
            if (key.startsWith("frm.")) {
                command.add("-D" + key + "=" + property.getValue());
            }
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CrashWorker.class.getName());
        Collections.addAll(command, args);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return new Child(process);
        } catch (IOException error) {
            throw new UncheckedIOException("cannot start child JVM", error);
        }
    }

    /** A child JVM whose output is collected on a background thread. */
    private static final class Child {
        private final Process process;
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch marker = new CountDownLatch(1);
        private final Thread reader;

        Child(Process process) {
            this.process = process;
            this.reader = new Thread(this::readOutput, "frm-child-output");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        private void readOutput() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                    if (line.startsWith(ScenarioContext.CRASH_MARKER)) {
                        marker.countDown();
                    }
                }
            } catch (IOException ignored) {
                // the process was killed
            } finally {
                marker.countDown();
            }
        }

        /** True when the crash marker appeared (false when the child ended or timed out first). */
        boolean awaitMarker(Duration timeout) {
            try {
                marker.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            synchronized (lines) {
                return lines.stream().anyMatch(line -> line.startsWith(ScenarioContext.CRASH_MARKER));
            }
        }

        boolean awaitExit(Duration timeout) {
            try {
                boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                reader.join(1_000L);
                return exited;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void kill() {
            process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
                reader.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        int exitCode() {
            return process.exitValue();
        }

        List<String> output() {
            synchronized (lines) {
                return List.copyOf(lines);
            }
        }
    }
}
