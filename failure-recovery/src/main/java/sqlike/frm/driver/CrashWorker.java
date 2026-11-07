package sqlike.frm.driver;

import java.nio.file.Path;
import java.time.Duration;

import sqlike.shared.RecoveryResult;

/**
 * Entry point of the child JVMs started by {@link CrashSimulator}; not meant to be run by hand.
 *
 * <pre>
 * run     &lt;scenario&gt; &lt;dataDir&gt; &lt;idleTimeoutMs&gt;   startup, workload, crash marker, wait for kill
 * recover &lt;dataDir&gt; &lt;idleTimeoutMs&gt;              startup recovery only, print the result, exit
 * </pre>
 *
 * The recover mode exits without flushing anything itself, so the data on disk afterwards shows
 * exactly what recovery made durable.
 */
public final class CrashWorker {

    private CrashWorker() {
    }

    public static void main(String[] args) {
        try {
            switch (args[0]) {
                case "run" -> run(args[1], Path.of(args[2]), Duration.ofMillis(Long.parseLong(args[3])));
                case "recover" -> recover(Path.of(args[1]), Duration.ofMillis(Long.parseLong(args[2])));
                default -> throw new IllegalArgumentException("unknown mode " + args[0]);
            }
        } catch (Throwable error) {
            System.out.println(CrashSimulator.CHILD_ERROR + "\t" + error);
            error.printStackTrace(System.out);
            System.out.flush();
            Runtime.getRuntime().halt(4);
        }
    }

    private static void run(String name, Path dataDir, Duration idleTimeout) {
        TestScenario scenario = Scenarios.byName(name)
                .orElseThrow(() -> new IllegalArgumentException("unknown scenario " + name));
        ScenarioContext ctx = ScenarioContext.openInChild(dataDir, FrmFactory.selected(), idleTimeout);
        ctx.startup();
        scenario.run(ctx); // returns only if the scenario forgot to crash
        System.out.println(CrashSimulator.NO_CRASH);
        System.out.flush();
        Runtime.getRuntime().halt(3);
    }

    private static void recover(Path dataDir, Duration idleTimeout) {
        ScenarioContext ctx = ScenarioContext.openInChild(dataDir, FrmFactory.selected(), idleTimeout);
        RecoveryResult result;
        try {
            result = ctx.startup();
        } catch (RuntimeException error) {
            System.out.println(CrashSimulator.RECOVERY_FAILED + "\t" + error);
            error.printStackTrace(System.out);
            System.out.flush();
            Runtime.getRuntime().halt(2);
            return;
        }
        System.out.println(String.join("\t", CrashSimulator.RECOVERY_RESULT,
                String.valueOf(result.lastTxId()), String.valueOf(result.lastTimestamp()),
                String.valueOf(result.lastRecoveredLSN()),
                result.checkpointLSN().map(String::valueOf).orElse("-")));
        System.out.flush();
        Runtime.getRuntime().halt(0); // no shutdown hooks: only what recovery made durable remains
    }
}
