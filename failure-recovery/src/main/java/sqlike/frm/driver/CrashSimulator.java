package sqlike.frm.driver;

import java.nio.file.Path;
import java.util.Optional;

import sqlike.shared.RecoveryResult;

/**
 * Simulates a crash by killing a child process without graceful shutdown, then recovers in a new
 * process (FR-08). A crash inside this JVM would let shutdown hooks flush data silently.
 *
 * <p>Milestone 1 MOCK: no child process is started yet.
 */
public class CrashSimulator {

    private final Path dataDir;

    public CrashSimulator(Path dataDir) {
        this.dataDir = dataDir;
    }

    /** Runs {@code scenario} in a child JVM until {@code point}, kills it, and returns its exit code. */
    public int runUntilCrash(TestScenario scenario, CrashPoint point) {
        // MOCK: TODO(FR1): start a child JVM on dataDir, wait for it to reach point, destroyForcibly().
        return 0;
    }

    /** Starts a fresh process on the same {@code dataDir} and runs startup recovery there. */
    public RecoveryResult restartAndRecover() {
        // MOCK: TODO(FR1): start a child JVM that calls frm.recover() and reports the result back.
        return new RecoveryResult(0L, 0L, 0L, Optional.empty());
    }
}
