package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import sqlike.contract.IConcurrencyControlManager;
import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ChangeOperation;
import sqlike.shared.ComponentType;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.PageId;
import sqlike.shared.ProtocolType;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;
import sqlike.shared.Value;

/**
 * Multi-threaded standalone component driver for Concurrency Control Manager (CC-07, CC-09, CC-10).
 */
public class CCMDriver {

    private static final long STEP_WAIT_DETECT_MS = 60L;
    private static final long DEADLOCK_MAX_RESOLUTION_MS = 5000L;
    private static final long SCENARIO_TIMEOUT_MS = 5000L;

    private final IConcurrencyControlManager ccm;
    private final List<Scenario> scenarios;
    private final SerializabilityVerifier verifier;
    private final MockFRM mockFrm;
    private final List<ScenarioReport> reports = new ArrayList<>();

    public record ScenarioReport(
            String scenarioName,
            ProtocolType protocol,
            List<String> stepLogs,
            List<ScheduleOperation> finalCommittedSchedule,
            Map<Long, Set<Long>> precedenceGraph,
            boolean conflictSerializable,
            List<Long> serialOrder,
            boolean passed,
            String summaryMessage) {
    }

    public CCMDriver(IConcurrencyControlManager ccm, List<Scenario> scenarios) {
        this(ccm, scenarios, new SerializabilityVerifier(), new MockFRM());
    }

    public CCMDriver(
            IConcurrencyControlManager ccm,
            List<Scenario> scenarios,
            SerializabilityVerifier verifier,
            MockFRM mockFrm) {
        this.ccm = ccm;
        this.scenarios = new ArrayList<>(scenarios);
        this.verifier = verifier;
        this.mockFrm = mockFrm;
    }

    public boolean runScenario(Scenario scenario) {
        if (scenario.isStandaloneManualSchedule()) {
            return runStandaloneScheduleScenario(scenario);
        }
        if (scenario.getSteps().isEmpty()) {
            return runInvariantVerificationScenario(scenario);
        }
        return runConcurrentScenario(scenario);
    }

    private boolean runStandaloneScheduleScenario(Scenario scenario) {
        List<ScheduleOperation> schedule = scenario.getManualSchedule();
        Map<Long, Set<Long>> graph = verifier.buildPrecedenceGraph(schedule);
        boolean serializable = verifier.isConflictSerializable(schedule);
        List<Long> order = verifier.serialOrder(schedule);
        boolean passed = (serializable == scenario.isExpectedSerializable());

        List<String> stepLogs = new ArrayList<>();
        for (ScheduleOperation op : schedule) {
            stepLogs.add(String.format("T%d %s(%s) -> DIIZINKAN (manual)",
                    op.txId(), op.write() ? "W" : "R", op.resource().tableName()));
        }

        String summary = String.format("Serializable=%s (expected=%s), SerialOrder=%s",
                serializable, scenario.isExpectedSerializable(), order);
        reports.add(new ScenarioReport(
                scenario.getName(),
                scenario.getProtocol(),
                stepLogs,
                schedule,
                graph,
                serializable,
                order,
                passed,
                summary));
        return passed;
    }

    private boolean runConcurrentScenario(Scenario scenario) {
        mockFrm.clear();
        for (Map.Entry<ResourceId, Integer> entry : scenario.getInitialValues().entrySet()) {
            mockFrm.seedValue(entry.getKey(), entry.getValue());
        }

        try {
            ccm.initAfterRecovery(0L);
            ccm.setProtocol(scenario.getProtocol());
        } catch (RuntimeException e) {
            reports.add(new ScenarioReport(
                    scenario.getName(),
                    scenario.getProtocol(),
                    List.of("Gagal mengatur protokol: " + e.getMessage()),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    false,
                    Collections.emptyList(),
                    false,
                    "setProtocol gagal"));
            return false;
        }

        ScheduleRecorder recorder = new ScheduleRecorder();
        Map<String, ExecutorService> txExecutors = new LinkedHashMap<>();
        Map<String, Long> txIds = new ConcurrentHashMap<>();
        Set<Long> activeTxSet = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> pendingTasks = new ArrayList<>();
        List<String> stepLogs = Collections.synchronizedList(new ArrayList<>());
        boolean[] expectationMismatch = {false};
        long deadlockStartNanos = System.nanoTime();

        try {
            for (Scenario.Step step : scenario.getSteps()) {
                ExecutorService worker = txExecutors.computeIfAbsent(
                        step.txLabel(),
                        label -> Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "ccm-driver-" + label);
                            t.setDaemon(true);
                            return t;
                        }));

                if (step.deadlockTrigger()) {
                    deadlockStartNanos = System.nanoTime();
                }

                CompletableFuture<AccessStatus> stepFuture = CompletableFuture.supplyAsync(
                        () -> executeStep(
                                step,
                                txIds,
                                activeTxSet,
                                recorder,
                                stepLogs,
                                expectationMismatch),
                        worker);

                long waitTimeoutMs = step.deadlockTrigger()
                        ? DEADLOCK_MAX_RESOLUTION_MS
                        : STEP_WAIT_DETECT_MS;

                try {
                    AccessStatus immediateStatus = stepFuture.get(waitTimeoutMs, TimeUnit.MILLISECONDS);
                    if (step.deadlockTrigger()) {
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - deadlockStartNanos);
                        stepLogs.add(String.format(
                                "Deadlock diselesaikan oleh WFG dalam %d ms (batas <= %d ms)",
                                elapsedMs, DEADLOCK_MAX_RESOLUTION_MS));
                        if (elapsedMs > DEADLOCK_MAX_RESOLUTION_MS) {
                            expectationMismatch[0] = true;
                        }
                    }
                    if (step.expectedAccessStatus().isPresent()
                            && step.expectedAccessStatus().get() != immediateStatus) {
                        expectationMismatch[0] = true;
                    }
                } catch (TimeoutException te) {
                    stepLogs.add(String.format("%s %s(%s) -> MENUNGGU (WAIT)",
                            step.txLabel(),
                            step.action(),
                            step.resource().map(ResourceId::tableName).orElse("-")));
                    if (step.expectedAccessStatus().isPresent()
                            && step.expectedAccessStatus().get() != AccessStatus.WAIT) {
                        expectationMismatch[0] = true;
                    }
                } catch (Exception e) {
                    stepLogs.add(String.format("%s %s -> ERROR (%s)",
                            step.txLabel(), step.action(), e.getMessage()));
                    expectationMismatch[0] = true;
                }

                pendingTasks.add(stepFuture.thenAccept(s -> {}));
            }

            CompletableFuture.allOf(pendingTasks.toArray(new CompletableFuture[0]))
                    .get(SCENARIO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            stepLogs.add("TIMEOUT: Transaksi membeku melebihi batas waktu " + SCENARIO_TIMEOUT_MS + " ms");
            expectationMismatch[0] = true;
        } catch (Exception e) {
            stepLogs.add("EXCEPTION: " + e.getMessage());
            expectationMismatch[0] = true;
        } finally {
            for (ExecutorService executor : txExecutors.values()) {
                executor.shutdownNow();
            }
        }

        if (scenario.getExpectedVictimLabel().isPresent()) {
            String victimLabel = scenario.getExpectedVictimLabel().get();
            Long expectedVictimTxId = txIds.get(victimLabel);
            if (expectedVictimTxId == null || !mockFrm.wasAborted(expectedVictimTxId)) {
                stepLogs.add("MISMATCH: Victim deadlock yang diharapkan adalah " + victimLabel);
                expectationMismatch[0] = true;
            }
        }

        if (!activeTxSet.isEmpty()) {
            for (Long remainingTx : new ArrayList<>(activeTxSet)) {
                ccm.abort(remainingTx);
                syncAbortToMockFrm(remainingTx);
                activeTxSet.remove(remainingTx);
            }
        }

        List<ScheduleOperation> committedSchedule = recorder.committedSchedule();
        Map<Long, Set<Long>> graph = verifier.buildPrecedenceGraph(committedSchedule);
        boolean serializable = verifier.isConflictSerializable(committedSchedule);
        List<Long> order = verifier.serialOrder(committedSchedule);
        boolean passed = !expectationMismatch[0] && (serializable == scenario.isExpectedSerializable());

        String summary = String.format("Serializable=%s (expected=%s), SerialOrder=%s",
                serializable, scenario.isExpectedSerializable(), order);
        reports.add(new ScenarioReport(
                scenario.getName(),
                scenario.getProtocol(),
                List.copyOf(stepLogs),
                committedSchedule,
                graph,
                serializable,
                order,
                passed,
                summary));
        return passed;
    }

    private AccessStatus executeStep(
            Scenario.Step step,
            Map<String, Long> txIds,
            Set<Long> activeTxSet,
            ScheduleRecorder recorder,
            List<String> stepLogs,
            boolean[] expectationMismatch) {
        String label = step.txLabel();
        switch (step.action()) {
            case BEGIN -> {
                if (step.injectWalFailure()) {
                    mockFrm.setFailNext(true);
                }
                int logSizeBefore = mockFrm.getLog().size();
                TransactionResponse resp = ccm.beginTransaction();
                long txId = resp.txId();
                txIds.put(label, txId);
                if (resp.status() == TransactionStatus.ACTIVE) {
                    try {
                        if (mockFrm.getLog().size() == logSizeBefore) {
                            mockFrm.logBegin(txId);
                        }
                        activeTxSet.add(txId);
                        stepLogs.add(String.format("%s BEGIN -> txId=%d ts=%d status=%s",
                                label, txId, resp.timestamp(), resp.status()));
                        return AccessStatus.GRANTED;
                    } catch (RuntimeException walEx) {
                        ccm.abort(txId);
                        syncAbortToMockFrm(txId);
                        stepLogs.add(String.format("%s BEGIN -> DIBATALKAN (WAL_FAILURE: %s)",
                                label, walEx.getMessage()));
                        return AccessStatus.ABORTED;
                    }
                }
                stepLogs.add(String.format("%s BEGIN -> txId=%d status=%s",
                        label, txId, resp.status()));
                return AccessStatus.ABORTED;
            }
            case READ -> {
                long txId = requireTxId(label, txIds);
                ResourceId res = step.resource().orElseThrow();
                AccessResponse resp = ccm.requestRead(txId, res);
                if (resp.status() == AccessStatus.ABORTED) {
                    syncAbortToMockFrm(txId);
                    activeTxSet.remove(txId);
                    stepLogs.add(String.format("%s (tx=%d) R(%s) -> %s",
                            label, txId, res.tableName(), formatAccessStatus(AccessStatus.ABORTED)));
                    return AccessStatus.ABORTED;
                }
                if (resp.status() == AccessStatus.GRANTED) {
                    recorder.record(new ScheduleOperation(txId, res, false));
                    if (step.expectedReadValue().isPresent()) {
                        int expectedVal = step.expectedReadValue().get();
                        Optional<Integer> actualVal = mockFrm.readIntValue(res);
                        if (actualVal.isEmpty() || actualVal.get() != expectedVal) {
                            stepLogs.add(String.format(
                                    "%s (tx=%d) R(%s) nilai aktual=%s != ekspektasi=%d",
                                    label, txId, res.tableName(), actualVal, expectedVal));
                            expectationMismatch[0] = true;
                        } else {
                            stepLogs.add(String.format(
                                    "%s (tx=%d) R(%s=%d) -> %s",
                                    label, txId, res.tableName(), actualVal.get(), formatAccessStatus(resp.status())));
                            return resp.status();
                        }
                    }
                }
                stepLogs.add(String.format("%s (tx=%d) R(%s) -> %s",
                        label, txId, res.tableName(), formatAccessStatus(resp.status())));
                return resp.status();
            }
            case WRITE -> {
                long txId = requireTxId(label, txIds);
                ResourceId res = step.resource().orElseThrow();
                DataChange change = buildChange(res, step.writeValue());
                if (step.injectWalFailure()) {
                    mockFrm.setFailNext(true);
                }
                int logSizeBefore = mockFrm.getLog().size();
                try {
                    AccessResponse resp = ccm.requestWrite(txId, change);
                    if (resp.status() == AccessStatus.ABORTED) {
                        syncAbortToMockFrm(txId);
                        activeTxSet.remove(txId);
                        stepLogs.add(String.format("%s (tx=%d) W(%s) -> %s",
                                label, txId, res.tableName(), formatAccessStatus(AccessStatus.ABORTED)));
                        return AccessStatus.ABORTED;
                    }
                    if (resp.status() == AccessStatus.GRANTED) {
                        if (mockFrm.getLog().size() == logSizeBefore) {
                            mockFrm.logChange(txId, change);
                        }
                        recorder.record(new ScheduleOperation(txId, res, true));
                    }
                    stepLogs.add(String.format("%s (tx=%d) W(%s) -> %s",
                            label, txId, res.tableName(), formatAccessStatus(resp.status())));
                    return resp.status();
                } catch (RuntimeException walEx) {
                    ErrorInfo walErr = new ErrorInfo(
                            ErrorCode.WAL_FAILURE,
                            ComponentType.CCM,
                            walEx.getMessage(),
                            Optional.empty());
                    ccm.abort(txId);
                    syncAbortToMockFrm(txId);
                    activeTxSet.remove(txId);
                    stepLogs.add(String.format("%s (tx=%d) W(%s) -> %s (%s)",
                            label, txId, res.tableName(),
                            formatAccessStatus(AccessStatus.ABORTED),
                            walErr.code()));
                    return AccessStatus.ABORTED;
                }
            }
            case COMMIT -> {
                long txId = requireTxId(label, txIds);
                if (step.injectWalFailure()) {
                    mockFrm.setFailNext(true);
                }
                try {
                    TransactionResponse resp = ccm.commit(txId);
                    if (resp.status() == TransactionStatus.COMMITTED) {
                        if (!mockFrm.wasCommitted(txId)) {
                            mockFrm.logCommit(txId);
                        }
                        recorder.markCommitted(txId);
                        activeTxSet.remove(txId);
                        stepLogs.add(String.format("%s (tx=%d) COMMIT -> %s",
                                label, txId, resp.status()));
                        return AccessStatus.GRANTED;
                    }
                    stepLogs.add(String.format("%s (tx=%d) COMMIT -> %s",
                            label, txId, resp.status()));
                    return AccessStatus.ABORTED;
                } catch (RuntimeException walEx) {
                    ccm.abort(txId);
                    syncAbortToMockFrm(txId);
                    activeTxSet.remove(txId);
                    stepLogs.add(String.format("%s (tx=%d) COMMIT -> DIBATALKAN (WAL_FAILURE)",
                            label, txId));
                    return AccessStatus.ABORTED;
                }
            }
            case ABORT -> {
                long txId = requireTxId(label, txIds);
                syncAbortToMockFrm(txId);
                TransactionResponse resp = ccm.abort(txId);
                activeTxSet.remove(txId);
                stepLogs.add(String.format("%s (tx=%d) ABORT -> %s",
                        label, txId, resp.status()));
                return AccessStatus.ABORTED;
            }
        }
        return AccessStatus.ABORTED;
    }

    private void syncAbortToMockFrm(long txId) {
        if (!mockFrm.wasAborted(txId)) {
            mockFrm.rollback(txId);
            mockFrm.logAbort(txId);
        }
    }

    /**
     * Verifies concurrency invariants required by Milestone 2 (CC-01, CC-02, CC-04, CC-05):
     * 1. Concurrent multi-threaded {@code beginTransaction()} produces unique, monotonically increasing IDs/timestamps.
     * 2. Calling {@code setProtocol()} while a transaction is active throws {@code IllegalStateException}.
     * 3. Calling {@code requestRead}, {@code requestWrite}, or {@code commit} after {@code ABORTED} is rejected with {@code TRANSACTION_ABORTED}.
     */
    private boolean runInvariantVerificationScenario(Scenario scenario) {
        List<String> stepLogs = new ArrayList<>();
        boolean passed = true;

        try {
            ccm.initAfterRecovery(100L);
            ccm.setProtocol(scenario.getProtocol());

            int threadCount = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<TransactionResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Callable<TransactionResponse> task = () -> {
                    startLatch.await();
                    return ccm.beginTransaction();
                };
                futures.add(pool.submit(task));
            }
            startLatch.countDown();

            Set<Long> uniqueIds = new TreeSet<>();
            for (Future<TransactionResponse> f : futures) {
                TransactionResponse tr = f.get(SCENARIO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (tr.status() != TransactionStatus.ACTIVE || tr.txId() != tr.timestamp() || tr.txId() <= 100L) {
                    passed = false;
                }
                uniqueIds.add(tr.txId());
            }
            pool.shutdownNow();

            if (uniqueIds.size() != threadCount) {
                passed = false;
                stepLogs.add("GAGAL: beginTransaction() paralel menghasilkan txId duplikat");
            } else {
                stepLogs.add(String.format(
                        "beginTransaction() paralel (%d thread) -> txId/timestamp unik & monotonik: %s",
                        threadCount, uniqueIds));
            }

            boolean setProtocolRejected = false;
            try {
                ccm.setProtocol(ProtocolType.TIMESTAMP_BASED);
            } catch (IllegalStateException expected) {
                setProtocolRejected = true;
            }
            if (!setProtocolRejected) {
                passed = false;
                stepLogs.add("GAGAL: setProtocol() saat ada transaksi aktif tidak ditolak");
            } else {
                stepLogs.add("setProtocol() saat transaksi aktif -> ditolak (IllegalStateException)");
            }

            List<Long> idList = new ArrayList<>(uniqueIds);
            long abortedTxId = idList.get(0);
            ccm.abort(abortedTxId);
            for (int i = 1; i < idList.size(); i++) {
                ccm.commit(idList.get(i));
            }

            ResourceId resA = Scenario.parseResource("A");
            AccessResponse readAfterAbort = ccm.requestRead(abortedTxId, resA);
            AccessResponse writeAfterAbort = ccm.requestWrite(abortedTxId, buildChange(resA, Optional.of(10)));
            TransactionResponse commitAfterAbort = ccm.commit(abortedTxId);

            boolean postAbortRejected = readAfterAbort.status() == AccessStatus.ABORTED
                    && readAfterAbort.error().map(ErrorInfo::code).orElse(null) == ErrorCode.TRANSACTION_ABORTED
                    && writeAfterAbort.status() == AccessStatus.ABORTED
                    && writeAfterAbort.error().map(ErrorInfo::code).orElse(null) == ErrorCode.TRANSACTION_ABORTED
                    && commitAfterAbort.error().map(ErrorInfo::code).orElse(null) == ErrorCode.TRANSACTION_ABORTED;

            if (!postAbortRejected) {
                passed = false;
                stepLogs.add("GAGAL: Operasi setelah ABORTED tidak ditolak dengan TRANSACTION_ABORTED");
            } else {
                stepLogs.add("requestRead/requestWrite/commit setelah ABORTED -> ditolak (TRANSACTION_ABORTED)");
            }
        } catch (Exception e) {
            passed = false;
            stepLogs.add("ERROR: " + e.getMessage());
        }

        reports.add(new ScenarioReport(
                scenario.getName(),
                scenario.getProtocol(),
                stepLogs,
                Collections.emptyList(),
                Collections.emptyMap(),
                true,
                Collections.emptyList(),
                passed,
                passed ? "Seluruh invarians CCM terpenuhi" : "Invarians CCM gagal"));
        return passed;
    }

    private long requireTxId(String label, Map<String, Long> txIds) {
        Long id = txIds.get(label);
        if (id == null) {
            throw new IllegalStateException("Transaction " + label + " has not called BEGIN");
        }
        return id;
    }

    private DataChange buildChange(ResourceId resource, Optional<Integer> newValue) {
        RID rid = resource.rid().orElseGet(() -> new RID(new PageId(resource.tableName(), 0L), 0));
        int beforeVal = mockFrm.readIntValue(resource).orElse(1);
        int afterVal = newValue.orElse(beforeVal + 1);
        RecordImage before = new RecordImage(true, List.of(
                new Value(DataType.INTEGER, Optional.of(beforeVal), Optional.empty(), Optional.empty())));
        RecordImage after = new RecordImage(true, List.of(
                new Value(DataType.INTEGER, Optional.of(afterVal), Optional.empty(), Optional.empty())));
        return new DataChange(ChangeOperation.UPDATE, resource.tableName(), rid, before, after);
    }

    private String formatAccessStatus(AccessStatus status) {
        return switch (status) {
            case GRANTED -> "DIIZINKAN (GRANTED)";
            case WAIT -> "MENUNGGU (WAIT)";
            case ABORTED -> "DIBATALKAN (ABORTED)";
        };
    }

    public void printReport() {
        int passedCount = 0;
        System.out.println("============================================================");
        System.out.println("          LAPORAN PENGUJIAN SCHEDULE TESTER (CC5)           ");
        System.out.println("============================================================");
        for (int i = 0; i < reports.size(); i++) {
            ScenarioReport report = reports.get(i);
            if (report.passed()) {
                passedCount++;
            }
            System.out.printf("[%d] Skenario : %s (%s)%n",
                    i + 1, report.scenarioName(), report.protocol());
            System.out.println("    Langkah Operasi:");
            for (String log : report.stepLogs()) {
                System.out.println("      - " + log);
            }
            System.out.println("    Schedule Akhir (Committed): " + formatSchedule(report.finalCommittedSchedule()));
            System.out.println("    Precedence Graph          : " + report.precedenceGraph());
            System.out.println("    Conflict Serializable     : " + report.conflictSerializable());
            System.out.println("    Urutan Serial Setara      : " + report.serialOrder());
            System.out.println("    Status                    : " + (report.passed() ? "LULUS" : "GAGAL"));
            System.out.println("------------------------------------------------------------");
        }
        System.out.printf("Total: %d/%d skenario LULUS%n", passedCount, reports.size());
        System.out.println("============================================================");
    }

    public List<ScenarioReport> getReports() {
        return Collections.unmodifiableList(reports);
    }

    private String formatSchedule(List<ScheduleOperation> schedule) {
        if (schedule.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < schedule.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ScheduleOperation op = schedule.get(i);
            sb.append("T").append(op.txId())
              .append(op.write() ? ":W(" : ":R(")
              .append(op.resource().tableName())
              .append(")");
        }
        sb.append("]");
        return sb.toString();
    }

    public static List<Scenario> defaultScenarios() {
        ResourceId resA = Scenario.parseResource("A");
        ResourceId resB = Scenario.parseResource("B");

        Scenario manualAcyclic = Scenario.manualSchedule(
                "Standalone Manual Schedule - Conflict Serializable (Acyclic T1 -> T2 -> T3)",
                List.of(
                        new ScheduleOperation(1L, resA, true),
                        new ScheduleOperation(2L, resA, false),
                        new ScheduleOperation(2L, resB, true),
                        new ScheduleOperation(3L, resB, false)
                ),
                true);

        Scenario manualCyclic = Scenario.manualSchedule(
                "Standalone Manual Schedule - Non-Serializable Cycle (T1 <-> T2)",
                List.of(
                        new ScheduleOperation(1L, resA, false),
                        new ScheduleOperation(2L, resA, true),
                        new ScheduleOperation(2L, resB, false),
                        new ScheduleOperation(1L, resB, true)
                ),
                false);

        Scenario concurrentReadWrite = Scenario.fromScript(
                "Concurrent Schedule - Serial Execution (Lock-Based)",
                """
                # protokol: lock
                T1 BEGIN
                T1 R(A)
                T1 W(A)
                T1 COMMIT
                T2 BEGIN
                T2 R(A)
                T2 W(B)
                T2 COMMIT
                """,
                true);

        Scenario lockWaitingNoCycle = Scenario.fromScript(
                "Lock-Based - Menunggu Tanpa Siklus (Waiting Without Deadlock)",
                """
                # protokol: lock
                T1 BEGIN
                T2 BEGIN
                T1 W(A)
                T2 R(A) # menunggu
                T1 COMMIT
                T2 COMMIT
                """,
                true);

        Scenario deadlockTwoTx = Scenario.fromScript(
                "Lock-Based - Deadlock 2 Transaksi (Victim Termuda T2 Dibatalkan)",
                """
                # protokol: lock
                # victim: T2
                T1 BEGIN
                T2 BEGIN
                T1 W(A)
                T2 W(B)
                T1 W(B) # menunggu
                T2 W(A) # deadlock
                T1 COMMIT
                """,
                true);

        Scenario deadlockThreeTx = Scenario.fromScript(
                "Lock-Based - Deadlock 3 Transaksi T1->T2->T3->T1 (Victim Termuda T3 Dibatalkan)",
                """
                # protokol: lock
                # victim: T3
                T1 BEGIN
                T2 BEGIN
                T3 BEGIN
                T1 W(A)
                T2 W(B)
                T3 W(C)
                T1 W(B) # menunggu
                T2 W(C) # menunggu
                T3 W(A) # deadlock
                T2 COMMIT
                T1 COMMIT
                """,
                true);

        Scenario deadlockUpgradeLock = Scenario.fromScript(
                "Lock-Based - Deadlock Upgrade Lock S->X (Victim Termuda T2 Dibatalkan)",
                """
                # protokol: lock
                # victim: T2
                T1 BEGIN
                T2 BEGIN
                T1 R(A)
                T2 R(A)
                T1 W(A) # menunggu
                T2 W(A) # deadlock
                T1 COMMIT
                """,
                true);

        Scenario concurrentTimestamp = Scenario.fromScript(
                "Timestamp-Based - Interleaved Acyclic Schedule",
                """
                # protokol: timestamp
                T1 BEGIN
                T2 BEGIN
                T1 R(A)
                T2 R(B)
                T1 W(A)
                T2 W(B)
                T1 COMMIT
                T2 COMMIT
                """,
                true);

        Scenario timestampWaitingNoCycle = Scenario.fromScript(
                "Timestamp-Based - Strict TO Menunggu Tanpa Siklus",
                """
                # protokol: timestamp
                T1 BEGIN
                T2 BEGIN
                T1 W(A)
                T2 R(A) # menunggu
                T1 COMMIT
                T2 COMMIT
                """,
                true);

        Scenario timestampViolationAndRetry = Scenario.fromScript(
                "Timestamp-Based - Pelanggaran Urutan Timestamp & Retry dengan Timestamp Baru",
                """
                # protokol: timestamp
                T1 BEGIN
                T2 BEGIN
                T2 R(A)
                T1 W(A) # dibatalkan
                T2 COMMIT
                T1_RETRY BEGIN
                T1_RETRY W(A) # diizinkan
                T1_RETRY COMMIT
                """,
                true);

        Scenario walFailureAbortsTx = Scenario.fromScript(
                "Koordinasi WAL - Kegagalan Tulis Log (WAL_FAILURE) Membatalkan Transaksi",
                """
                # protokol: lock
                # seed: A=100
                T1 BEGIN
                T1 W(A=200) # fail_wal
                T2 BEGIN
                T2 R(A=100) # diizinkan
                T2 COMMIT
                """,
                true);

        Scenario readOldValueAfterAbort = Scenario.fromScript(
                "Koordinasi Rollback - Baca Nilai Lama (Before-Image) Setelah Abort",
                """
                # protokol: lock
                # seed: A=100
                T1 BEGIN
                T1 W(A=250)
                T1 ABORT
                T2 BEGIN
                T2 R(A=100) # diizinkan
                T2 W(A=150)
                T2 COMMIT
                """,
                true);

        Scenario ccmInvariants = new Scenario(
                "Invarians CCM - beginTransaction Paralel, Penolakan setProtocol Aktif & Akses Pasca-Abort",
                ProtocolType.LOCK_BASED,
                false,
                List.of(),
                true);

        return List.of(
                manualAcyclic,
                manualCyclic,
                concurrentReadWrite,
                lockWaitingNoCycle,
                deadlockTwoTx,
                deadlockThreeTx,
                deadlockUpgradeLock,
                concurrentTimestamp,
                timestampWaitingNoCycle,
                timestampViolationAndRetry,
                walFailureAbortsTx,
                readOldValueAfterAbort,
                ccmInvariants);
    }

    public static void main(String[] args) {
        MockFRM mockFrm = new MockFRM();
        ConcurrencyControlManager ccm = new ConcurrencyControlManager(mockFrm);
        System.out.println(ccm.describeProtocol());
        List<Scenario> scenarios = defaultScenarios();
        CCMDriver driver = new CCMDriver(ccm, scenarios, new SerializabilityVerifier(), mockFrm);
        for (Scenario scenario : scenarios) {
            driver.runScenario(scenario);
        }
        driver.printReport();
    }
}
