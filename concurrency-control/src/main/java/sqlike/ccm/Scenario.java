package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import sqlike.shared.AccessStatus;
import sqlike.shared.PageId;
import sqlike.shared.ProtocolType;
import sqlike.shared.RID;
import sqlike.shared.ResourceId;

/**
 * Represents a concurrency control test scenario for {@link CCMDriver} (CC-07, CC-09, CC-10).
 *
 * <p>Supports concurrent multi-threaded execution against {@code IConcurrencyControlManager},
 * standalone manual schedule verification via {@link SerializabilityVerifier}, WAL failure
 * injection, rollback before-image verification, and protocol invariant checks.
 */
public class Scenario {

    public enum ActionType {
        BEGIN,
        READ,
        WRITE,
        COMMIT,
        ABORT
    }

    public record Step(
            String txLabel,
            ActionType action,
            Optional<ResourceId> resource,
            Optional<AccessStatus> expectedAccessStatus,
            Optional<Integer> writeValue,
            Optional<Integer> expectedReadValue,
            boolean injectWalFailure,
            boolean deadlockTrigger) {

        public Step(
                String txLabel,
                ActionType action,
                Optional<ResourceId> resource,
                Optional<AccessStatus> expectedAccessStatus) {
            this(
                    txLabel,
                    action,
                    resource,
                    expectedAccessStatus,
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    false);
        }

        public Step {
            resource = resource == null ? Optional.empty() : resource;
            expectedAccessStatus = expectedAccessStatus == null ? Optional.empty() : expectedAccessStatus;
            writeValue = writeValue == null ? Optional.empty() : writeValue;
            expectedReadValue = expectedReadValue == null ? Optional.empty() : expectedReadValue;
        }
    }

    private final String name;
    private final ProtocolType protocol;
    private final boolean deadlockScenario;
    private final List<Step> steps;
    private final boolean expectedSerializable;
    private final boolean standaloneManualSchedule;
    private final List<ScheduleOperation> manualSchedule;
    private final Map<ResourceId, Integer> initialValues;
    private final Optional<String> expectedVictimLabel;
    private String script;

    public Scenario(
            String name,
            ProtocolType protocol,
            boolean deadlockScenario,
            List<Step> steps,
            boolean expectedSerializable) {
        this(name, protocol, deadlockScenario, steps, expectedSerializable, Map.of(), Optional.empty());
    }

    public Scenario(
            String name,
            ProtocolType protocol,
            boolean deadlockScenario,
            List<Step> steps,
            boolean expectedSerializable,
            Map<ResourceId, Integer> initialValues,
            Optional<String> expectedVictimLabel) {
        this.name = name;
        this.protocol = protocol;
        this.deadlockScenario = deadlockScenario;
        this.steps = List.copyOf(steps);
        this.expectedSerializable = expectedSerializable;
        this.standaloneManualSchedule = false;
        this.manualSchedule = Collections.emptyList();
        this.initialValues = initialValues == null ? Map.of() : Map.copyOf(initialValues);
        this.expectedVictimLabel = expectedVictimLabel == null ? Optional.empty() : expectedVictimLabel;
    }

    private Scenario(
            String name,
            List<ScheduleOperation> manualSchedule,
            boolean expectedSerializable) {
        this.name = name;
        this.protocol = ProtocolType.LOCK_BASED;
        this.deadlockScenario = false;
        this.steps = Collections.emptyList();
        this.expectedSerializable = expectedSerializable;
        this.standaloneManualSchedule = true;
        this.manualSchedule = List.copyOf(manualSchedule);
        this.initialValues = Map.of();
        this.expectedVictimLabel = Optional.empty();
    }

    public static Scenario manualSchedule(
            String name,
            List<ScheduleOperation> schedule,
            boolean expectedSerializable) {
        return new Scenario(name, schedule, expectedSerializable);
    }

    /**
     * Parses a declarative schedule script (Guidebook section 5.5.3 format).
     *
     * <pre>
     * # protokol: lock
     * # seed: A=100
     * # victim: T2
     * T1 BEGIN
     * T2 BEGIN
     * T1 W(A=200)
     * T2 W(B)
     * T1 W(B) # menunggu
     * T2 W(A) # deadlock
     * </pre>
     */
    public static Scenario fromScript(String name, String script, boolean expectedSerializable) {
        ProtocolType protocol = ProtocolType.LOCK_BASED;
        boolean deadlock = false;
        List<Step> steps = new ArrayList<>();
        Map<ResourceId, Integer> seeds = new LinkedHashMap<>();
        Optional<String> expectedVictim = Optional.empty();

        String[] lines = script.split("\\R");
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                String headerBody = trimmed.substring(1).trim();
                String lowerHeader = headerBody.toLowerCase(Locale.ROOT);
                if (lowerHeader.startsWith("protokol:") || lowerHeader.startsWith("protocol:")) {
                    if (lowerHeader.contains("timestamp")) {
                        protocol = ProtocolType.TIMESTAMP_BASED;
                    } else if (lowerHeader.contains("lock")) {
                        protocol = ProtocolType.LOCK_BASED;
                    }
                } else if (lowerHeader.startsWith("seed:")) {
                    String seedSpec = headerBody.substring("seed:".length()).trim();
                    for (String part : seedSpec.split(",")) {
                        String[] kv = part.trim().split("=", 2);
                        if (kv.length == 2) {
                            seeds.put(parseResource(kv[0].trim()), Integer.parseInt(kv[1].trim()));
                        }
                    }
                } else if (lowerHeader.startsWith("victim:")) {
                    expectedVictim = Optional.of(headerBody.substring("victim:".length()).trim());
                }
                continue;
            }

            String codePart = trimmed;
            String commentPart = "";
            int hashIdx = trimmed.indexOf('#');
            if (hashIdx >= 0) {
                codePart = trimmed.substring(0, hashIdx).trim();
                commentPart = trimmed.substring(hashIdx + 1).trim().toLowerCase(Locale.ROOT);
            }
            if (codePart.isEmpty()) {
                continue;
            }

            Optional<AccessStatus> expectedStatus = Optional.empty();
            boolean stepDeadlock = false;
            boolean injectWalFail = false;

            if (commentPart.contains("deadlock")) {
                deadlock = true;
                stepDeadlock = true;
                expectedStatus = Optional.of(AccessStatus.ABORTED);
            }
            if (commentPart.contains("fail_wal") || commentPart.contains("wal_failure")) {
                injectWalFail = true;
                expectedStatus = Optional.of(AccessStatus.ABORTED);
            }
            if (commentPart.contains("menunggu") || commentPart.contains("wait")) {
                expectedStatus = Optional.of(AccessStatus.WAIT);
            } else if (commentPart.contains("dibatalkan") || commentPart.contains("abort")) {
                expectedStatus = Optional.of(AccessStatus.ABORTED);
            } else if (commentPart.contains("diizinkan") || commentPart.contains("grant")) {
                expectedStatus = Optional.of(AccessStatus.GRANTED);
            }

            String[] tokens = codePart.split("\\s+", 2);
            if (tokens.length < 2) {
                throw new IllegalArgumentException("Invalid schedule step: " + rawLine);
            }
            String txLabel = tokens[0].trim();
            String opToken = tokens[1].trim();
            String upperOp = opToken.toUpperCase(Locale.ROOT);

            if (upperOp.equals("BEGIN")) {
                steps.add(new Step(
                        txLabel, ActionType.BEGIN, Optional.empty(), expectedStatus,
                        Optional.empty(), Optional.empty(), injectWalFail, stepDeadlock));
            } else if (upperOp.equals("COMMIT")) {
                steps.add(new Step(
                        txLabel, ActionType.COMMIT, Optional.empty(), expectedStatus,
                        Optional.empty(), Optional.empty(), injectWalFail, stepDeadlock));
            } else if (upperOp.equals("ABORT")) {
                steps.add(new Step(
                        txLabel, ActionType.ABORT, Optional.empty(), expectedStatus,
                        Optional.empty(), Optional.empty(), injectWalFail, stepDeadlock));
            } else if (upperOp.startsWith("R(") && opToken.endsWith(")")) {
                String inner = opToken.substring(2, opToken.length() - 1).trim();
                String resName = inner;
                Optional<Integer> expectedReadVal = Optional.empty();
                int eqIdx = inner.indexOf('=');
                if (eqIdx > 0) {
                    resName = inner.substring(0, eqIdx).trim();
                    expectedReadVal = Optional.of(Integer.parseInt(inner.substring(eqIdx + 1).trim()));
                }
                steps.add(new Step(
                        txLabel, ActionType.READ, Optional.of(parseResource(resName)), expectedStatus,
                        Optional.empty(), expectedReadVal, injectWalFail, stepDeadlock));
            } else if (upperOp.startsWith("W(") && opToken.endsWith(")")) {
                String inner = opToken.substring(2, opToken.length() - 1).trim();
                String resName = inner;
                Optional<Integer> writeVal = Optional.empty();
                int eqIdx = inner.indexOf('=');
                if (eqIdx > 0) {
                    resName = inner.substring(0, eqIdx).trim();
                    writeVal = Optional.of(Integer.parseInt(inner.substring(eqIdx + 1).trim()));
                }
                steps.add(new Step(
                        txLabel, ActionType.WRITE, Optional.of(parseResource(resName)), expectedStatus,
                        writeVal, Optional.empty(), injectWalFail, stepDeadlock));
            } else {
                throw new IllegalArgumentException("Unrecognized operation in schedule: " + rawLine);
            }
        }

        Scenario scenario = new Scenario(name, protocol, deadlock, steps, expectedSerializable, seeds, expectedVictim);
        scenario.script = script;
        return scenario;
    }

    /**
     * The same script run under {@code other}, for scenarios whose expectations do not depend on how
     * the protocol resolves conflicts (WAL failures, rollback, serial execution). Empty for scenarios
     * not built from a script.
     */
    public Optional<Scenario> asProtocol(ProtocolType other) {
        if (script == null) {
            return Optional.empty();
        }
        String header = other == ProtocolType.TIMESTAMP_BASED ? "# protokol: timestamp" : "# protokol: lock";
        String rewritten = script.replaceAll("(?im)^(\\s*)#\\s*proto(?:kol|col):.*$", "$1" + header);
        return Optional.of(fromScript(name + " [" + other + "]", rewritten, expectedSerializable));
    }

    /** True when a COMMIT step injects a WAL failure: its outcome is unknown, so its locks stay until recovery. */
    public boolean expectsUnresolvedCommit() {
        return steps.stream().anyMatch(step -> step.action() == ActionType.COMMIT && step.injectWalFailure());
    }

    public static ResourceId parseResource(String symbol) {
        int colonIdx = symbol.indexOf(':');
        if (colonIdx > 0) {
            String table = symbol.substring(0, colonIdx).trim();
            int slot = Integer.parseInt(symbol.substring(colonIdx + 1).trim());
            return Resources.record(table, new RID(new PageId(table, 0L), slot));
        }
        int slot = Math.abs(symbol.hashCode() % 10_000);
        return Resources.record("items_" + symbol, new RID(new PageId("items_" + symbol, 0L), slot));
    }

    public String getName() {
        return name;
    }

    public ProtocolType getProtocol() {
        return protocol;
    }

    public boolean isDeadlockScenario() {
        return deadlockScenario;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public boolean isExpectedSerializable() {
        return expectedSerializable;
    }

    public boolean isStandaloneManualSchedule() {
        return standaloneManualSchedule;
    }

    public List<ScheduleOperation> getManualSchedule() {
        return manualSchedule;
    }

    public Map<ResourceId, Integer> getInitialValues() {
        return initialValues;
    }

    public Optional<String> getExpectedVictimLabel() {
        return expectedVictimLabel;
    }
}
