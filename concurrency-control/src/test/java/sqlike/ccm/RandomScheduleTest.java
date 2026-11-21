package sqlike.ccm;

import static sqlike.ccm.Check.change;
import static sqlike.ccm.Check.record;
import static sqlike.ccm.Check.require;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ProtocolType;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;

/**
 * Random concurrent transactions through the CCM facade: many seeds, both protocols. Every run must finish
 * without a hang, the committed schedule must be conflict serializable, aborted transactions must not appear
 * in it, and no lock or protocol data may be left behind.
 */
public final class RandomScheduleTest {

    private static final String[] TABLES = {"a", "b", "c"};
    /** Eight seeds keep the regular run short; raise it with -Dseeds=N to hunt for rare interleavings. */
    private static final int SEEDS = Integer.getInteger("seeds", 8);

    public static void main(String[] args) throws Exception {
        Check check = new Check("CC5 RandomSchedule");
        for (ProtocolType protocol : ProtocolType.values()) {
            check.test(protocol + ": random concurrent transactions stay serializable and leave nothing behind",
                    () -> {
                        for (long seed = 1; seed <= SEEDS; seed++) {
                            run(protocol, seed);
                        }
                    });
        }
        for (ProtocolType protocol : ProtocolType.values()) {
            check.test(protocol + ": injected WAL and rollback failures never hang a client or leave locks behind", () -> {
                for (long seed = 1; seed <= SEEDS; seed++) {
                    run(protocol, 10_000L + seed, true);
                }
            });
        }
        check.done();
    }

    private static void run(ProtocolType protocol, long seed) throws Exception {
        run(protocol, seed, false);
    }

    private static void run(ProtocolType protocol, long seed, boolean injectFailures) throws Exception {
        MockFRM frm = new MockFRM();
        if (injectFailures) {
            // The first few change logs fail, and the first rollbacks fail twice (the bridge retries three times).
            frm.setFailOnOperation("logChange", 6);
            frm.setFailOnOperation("rollback", 2);
        }
        ConcurrencyControlManager ccm = new ConcurrencyControlManager(frm, new CCMConfig(protocol, 5000L, 3, 5L, 500L));
        ccm.initAfterRecovery(0);
        int clients = 6;
        int perClient = 12;
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<?>> futures = new ArrayList<>();
        for (int client = 0; client < clients; client++) {
            Random random = new Random(seed * 1_000L + client);
            futures.add(pool.submit(() -> {
                for (int done = 0; done < perClient; ) {
                    TransactionResponse begin = ccm.beginTransaction();
                    long tx = begin.txId();
                    boolean ok = true;
                    int ops = 1 + random.nextInt(4);
                    for (int i = 0; i < ops && ok; i++) {
                        String table = TABLES[random.nextInt(TABLES.length)];
                        AccessResponse response;
                        if (random.nextBoolean()) {
                            response = ccm.requestRead(tx, record(table, random.nextInt(3)));
                        } else {
                            response = ccm.requestWriteAccess(tx, record(table, random.nextInt(3)));
                            if (response.status() == AccessStatus.GRANTED) {
                                response = ccm.requestWrite(tx, change(table, random.nextInt(3)));
                            }
                        }
                        ok = response.status() == AccessStatus.GRANTED;
                    }
                    if (ok && random.nextInt(8) == 0) {
                        require(ccm.abort(tx).status() == TransactionStatus.ABORTED, "user abort must finish");
                        aborts.incrementAndGet();
                        done++;
                    } else if (ok) {
                        require(ccm.commit(tx).status() == TransactionStatus.COMMITTED, "commit must finish");
                        commits.incrementAndGet();
                        done++;
                    } else {
                        // The protocol aborted this transaction (deadlock victim or timestamp violation).
                        TransactionResponse state = ccm.abort(tx);
                        require(state.status() == TransactionStatus.ABORTED, "an aborted transaction must end ABORTED");
                        aborts.incrementAndGet();
                        try {
                            Thread.sleep(random.nextInt(4));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }));
        }
        pool.shutdown();
        for (Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                pool.shutdownNow();
                throw new AssertionError(protocol + " seed " + seed + ": a client hung (commits " + commits + ", aborts " + aborts + ")");
            }
        }
        List<ScheduleOperation> committed = ccm.getRecorder().committedSchedule();
        SerializabilityVerifier verifier = new SerializabilityVerifier();
        require(verifier.isConflictSerializable(committed),
                protocol + " seed " + seed + ": the committed schedule is not conflict serializable");
        require(commits.get() > 0, protocol + " seed " + seed + ": nothing committed");
        require(ccm.leftoverState().isEmpty(), protocol + " seed " + seed + ": leftover state " + ccm.leftoverState());
    }
}
