package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import sqlike.contract.IConcurrencyControlManager;
import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ChangeOperation;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.PageId;
import sqlike.shared.ProtocolType;
import sqlike.shared.RID;
import sqlike.shared.RecordImage;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;
import sqlike.shared.Value;

/**
 * Multi-threaded standalone component driver for Concurrency Control Manager (CC-09, CC-10).
 */
public class CCMDriver {

    private static final long STEP_WAIT_DETECT_MS = 60L;
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
        ScheduleRecorder recorder = new ScheduleRecorder();
        try {
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

        Map<String, ExecutorService> txExecutors = new LinkedHashMap<>();
        Map<String, Long> txIds = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> pendingTasks = new ArrayList<>();
        List<String> stepLogs = Collections.synchronizedList(new ArrayList<>());
        boolean[] expectationMismatch = {false};

        try {
            for (Scenario.Step step : scenario.getSteps()) {
                ExecutorService worker = txExecutors.computeIfAbsent(
                        step.txLabel(),
                        label -> Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "ccm-driver-" + label);
                            t.setDaemon(true);
                            return t;
                        }));

                CompletableFuture<AccessStatus> stepFuture = CompletableFuture.supplyAsync(
                        () -> executeStep(step, txIds, recorder, stepLogs),
                        worker);

                try {
                    AccessStatus immediateStatus = stepFuture.get(STEP_WAIT_DETECT_MS, TimeUnit.MILLISECONDS);
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
            ScheduleRecorder recorder,
            List<String> stepLogs) {
        String label = step.txLabel();
        switch (step.action()) {
            case BEGIN -> {
                TransactionResponse resp = ccm.beginTransaction();
                txIds.put(label, resp.txId());
                stepLogs.add(String.format("%s BEGIN -> txId=%d status=%s",
                        label, resp.txId(), resp.status()));
                return resp.status() == TransactionStatus.ACTIVE ? AccessStatus.GRANTED : AccessStatus.ABORTED;
            }
            case READ -> {
                long txId = requireTxId(label, txIds);
                ResourceId res = step.resource().orElseThrow();
                AccessResponse resp = ccm.requestRead(txId, res);
                stepLogs.add(String.format("%s (tx=%d) R(%s) -> %s",
                        label, txId, res.tableName(), formatAccessStatus(resp.status())));
                if (resp.status() == AccessStatus.GRANTED) {
                    recorder.record(new ScheduleOperation(txId, res, false));
                }
                return resp.status();
            }
            case WRITE -> {
                long txId = requireTxId(label, txIds);
                ResourceId res = step.resource().orElseThrow();
                DataChange change = buildDummyChange(res);
                AccessResponse resp = ccm.requestWrite(txId, change);
                stepLogs.add(String.format("%s (tx=%d) W(%s) -> %s",
                        label, txId, res.tableName(), formatAccessStatus(resp.status())));
                if (resp.status() == AccessStatus.GRANTED) {
                    recorder.record(new ScheduleOperation(txId, res, true));
                }
                return resp.status();
            }
            case COMMIT -> {
                long txId = requireTxId(label, txIds);
                TransactionResponse resp = ccm.commit(txId);
                stepLogs.add(String.format("%s (tx=%d) COMMIT -> %s",
                        label, txId, resp.status()));
                if (resp.status() == TransactionStatus.COMMITTED) {
                    recorder.markCommitted(txId);
                    return AccessStatus.GRANTED;
                }
                return AccessStatus.ABORTED;
            }
            case ABORT -> {
                long txId = requireTxId(label, txIds);
                TransactionResponse resp = ccm.abort(txId);
                stepLogs.add(String.format("%s (tx=%d) ABORT -> %s",
                        label, txId, resp.status()));
                return AccessStatus.ABORTED;
            }
        }
        return AccessStatus.ABORTED;
    }

    private long requireTxId(String label, Map<String, Long> txIds) {
        Long id = txIds.get(label);
        if (id == null) {
            throw new IllegalStateException("Transaction " + label + " has not called BEGIN");
        }
        return id;
    }

    private DataChange buildDummyChange(ResourceId resource) {
        RID rid = resource.rid().orElseGet(() -> new RID(new PageId(resource.tableName(), 0L), 0));
        RecordImage before = new RecordImage(true, List.of(
                new Value(DataType.INTEGER, Optional.of(1), Optional.empty(), Optional.empty())));
        RecordImage after = new RecordImage(true, List.of(
                new Value(DataType.INTEGER, Optional.of(2), Optional.empty(), Optional.empty())));
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

    /**
     * Default Milestone 1 mock CCM (always GRANTED) used when running CCMDriver standalone
     * before CC1's ConcurrencyControlManager implementation is connected.
     */
    public static class Milestone1MockCCM implements IConcurrencyControlManager {
        private final AtomicLong txCounter = new AtomicLong(0);
        private final FRMBridge frmBridge;
        private volatile ProtocolType activeProtocol = ProtocolType.LOCK_BASED;

        public Milestone1MockCCM(MockFRM mockFrm) {
            this.frmBridge = new FRMBridge(mockFrm);
        }

        @Override
        public TransactionResponse beginTransaction() {
            long id = txCounter.incrementAndGet();
            frmBridge.logBegin(id);
            return new TransactionResponse(id, id, TransactionStatus.ACTIVE, Optional.empty());
        }

        @Override
        public AccessResponse requestRead(long txId, ResourceId resource) {
            return new AccessResponse(AccessStatus.GRANTED, Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public AccessResponse requestWrite(long txId, DataChange change) {
            long lsn = frmBridge.logWrite(txId, change);
            return new AccessResponse(AccessStatus.GRANTED, Optional.of(lsn), Optional.empty(), Optional.empty());
        }

        @Override
        public TransactionResponse commit(long txId) {
            frmBridge.logCommit(txId);
            return new TransactionResponse(txId, txId, TransactionStatus.COMMITTED, Optional.empty());
        }

        @Override
        public TransactionResponse abort(long txId) {
            frmBridge.rollbackThenLogAbort(txId);
            return new TransactionResponse(txId, txId, TransactionStatus.ABORTED, Optional.empty());
        }

        @Override
        public void setProtocol(ProtocolType protocol) {
            this.activeProtocol = protocol;
        }

        @Override
        public void initAfterRecovery(long lastTxId) {
            txCounter.set(lastTxId);
        }

        @Override
        public void pauseNewTransactions() {
        }

        @Override
        public void awaitNoActiveTransactions() {
        }

        @Override
        public void resumeTransactions() {
        }

        public ProtocolType getActiveProtocol() {
            return activeProtocol;
        }
    }

    public static List<Scenario> defaultScenarios() {
        ResourceId resA = Scenario.parseResource("A");
        ResourceId resB = Scenario.parseResource("B");

        Scenario manualCyclic = Scenario.manualSchedule(
                "Standalone Manual Schedule - Non-Serializable Cycle (T1 <-> T2)",
                List.of(
                        new ScheduleOperation(1L, resA, false),
                        new ScheduleOperation(2L, resA, true),
                        new ScheduleOperation(2L, resB, false),
                        new ScheduleOperation(1L, resB, true)
                ),
                false);

        Scenario manualAcyclic = Scenario.manualSchedule(
                "Standalone Manual Schedule - Conflict Serializable (Acyclic T1 -> T2 -> T3)",
                List.of(
                        new ScheduleOperation(1L, resA, true),
                        new ScheduleOperation(2L, resA, false),
                        new ScheduleOperation(2L, resB, true),
                        new ScheduleOperation(3L, resB, false)
                ),
                true);

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

        Scenario concurrentTimestamp = Scenario.fromScript(
                "Concurrent Schedule - Interleaved Acyclic (Timestamp-Based)",
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

        Scenario abortedTxIgnored = Scenario.fromScript(
                "Concurrent Schedule - Aborted Transaction Excluded from Precedence Graph",
                """
                # protokol: lock
                T1 BEGIN
                T2 BEGIN
                T1 W(A)
                T2 W(A)
                T1 ABORT
                T2 COMMIT
                """,
                true);

        return List.of(
                manualAcyclic,
                manualCyclic,
                concurrentReadWrite,
                concurrentTimestamp,
                abortedTxIgnored);
    }

    public static void main(String[] args) {
        MockFRM mockFrm = new MockFRM();
        IConcurrencyControlManager ccm = new Milestone1MockCCM(mockFrm);
        List<Scenario> scenarios = defaultScenarios();
        CCMDriver driver = new CCMDriver(ccm, scenarios, new SerializabilityVerifier(), mockFrm);
        for (Scenario scenario : scenarios) {
            driver.runScenario(scenario);
        }
        driver.printReport();
    }
}
