package sqlike.qp.stub;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import sqlike.qp.QpBootstrap;
import sqlike.qp.QpConfiguration;
import sqlike.qp.QueryServer;

/**
 * SQLike server entrypoint with stub SM/CCM/FRM.
 * Usage: java sqlike.qp.stub.QpServerMain [--data DIR] [--port N] [--max-clients N]
 * TODO(M3): use new QpBootstrap(queryProcessor, server) with the real SM, CCM, and FRM.
 */
public final class QpServerMain {
    private QpServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        Path dataDir = Path.of("qp-data");
        List<String> configArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data") && i + 1 < args.length) {
                dataDir = Path.of(args[++i]);
            } else {
                configArgs.add(args[i]);
            }
        }
        QpConfiguration config;
        try {
            config = QpConfiguration.fromArgs(configArgs.toArray(String[]::new));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        StubQpRuntime runtime = StubQpRuntime.open(dataDir, config);
        QueryServer server = new QueryServer(runtime.processor, config);
        QpBootstrap bootstrap = new QpBootstrap(runtime.transactions, runtime.transactions, server);
        try {
            bootstrap.start();
        } catch (RuntimeException e) {
            System.err.println("SQLike server failed to start (" + bootstrap.getState() + "): " + e.getMessage());
            System.exit(1);
            return;
        }

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bootstrap.stop();
            runtime.storage.flushAll();
            stopped.countDown();
        }, "sqlike-server-shutdown"));

        System.out.println("SQLike server " + bootstrap.getState() + " on port " + server.getBoundPort()
                + " (max " + config.getMaxClients() + " clients, data " + dataDir.toAbsolutePath() + ")");
        System.out.println("Connect with: java -cp <classes> sqlike.qp.CliClient localhost " + server.getBoundPort());
        System.out.println("Press Ctrl+C to stop.");
        stopped.await();
    }
}
