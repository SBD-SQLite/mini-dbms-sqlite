package sqlike.ccm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import sqlike.shared.AccessStatus;
import sqlike.shared.PageId;
import sqlike.shared.ProtocolType;
import sqlike.shared.RID;
import sqlike.shared.ResourceId;

/**
 * Represents a concurrency control test scenario for {@link CCMDriver} (CC-09, CC-10).
 *
 * <p>Supports both concurrent multi-threaded execution against {@code IConcurrencyControlManager}
 * and standalone manual schedule verification via {@link SerializabilityVerifier}.
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
            Optional<AccessStatus> expectedAccessStatus) {

        public Step {
            resource = resource == null ? Optional.empty() : resource;
            expectedAccessStatus = expectedAccessStatus == null ? Optional.empty() : expectedAccessStatus;
        }
    }

    private final String name;
    private final ProtocolType protocol;
    private final boolean deadlockScenario;
    private final List<Step> steps;
    private final boolean expectedSerializable;
    private final boolean standaloneManualSchedule;
    private final List<ScheduleOperation> manualSchedule;

    public Scenario(
            String name,
            ProtocolType protocol,
            boolean deadlockScenario,
            List<Step> steps,
            boolean expectedSerializable) {
        this.name = name;
        this.protocol = protocol;
        this.deadlockScenario = deadlockScenario;
        this.steps = List.copyOf(steps);
        this.expectedSerializable = expectedSerializable;
        this.standaloneManualSchedule = false;
        this.manualSchedule = Collections.emptyList();
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
     * T1 BEGIN
     * T2 BEGIN
     * T1 W(A)
     * T2 W(B)
     * T1 W(B) # menunggu
     * T2 W(A) # deadlock
     * </pre>
     */
    public static Scenario fromScript(String name, String script, boolean expectedSerializable) {
        ProtocolType protocol = ProtocolType.LOCK_BASED;
        boolean deadlock = false;
        List<Step> steps = new ArrayList<>();

        String[] lines = script.split("\\R");
        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                String lowerHeader = trimmed.toLowerCase(Locale.ROOT);
                if (lowerHeader.contains("protokol:") || lowerHeader.contains("protocol:")) {
                    if (lowerHeader.contains("timestamp")) {
                        protocol = ProtocolType.TIMESTAMP_BASED;
                    } else if (lowerHeader.contains("lock")) {
                        protocol = ProtocolType.LOCK_BASED;
                    }
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
            if (commentPart.contains("deadlock")) {
                deadlock = true;
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
                steps.add(new Step(txLabel, ActionType.BEGIN, Optional.empty(), Optional.empty()));
            } else if (upperOp.equals("COMMIT")) {
                steps.add(new Step(txLabel, ActionType.COMMIT, Optional.empty(), Optional.empty()));
            } else if (upperOp.equals("ABORT")) {
                steps.add(new Step(txLabel, ActionType.ABORT, Optional.empty(), Optional.empty()));
            } else if (upperOp.startsWith("R(") && opToken.endsWith(")")) {
                String resName = opToken.substring(2, opToken.length() - 1).trim();
                steps.add(new Step(txLabel, ActionType.READ, Optional.of(parseResource(resName)), expectedStatus));
            } else if (upperOp.startsWith("W(") && opToken.endsWith(")")) {
                String resName = opToken.substring(2, opToken.length() - 1).trim();
                steps.add(new Step(txLabel, ActionType.WRITE, Optional.of(parseResource(resName)), expectedStatus));
            } else {
                throw new IllegalArgumentException("Unrecognized operation in schedule: " + rawLine);
            }
        }

        return new Scenario(name, protocol, deadlock, steps, expectedSerializable);
    }

    public static ResourceId parseResource(String symbol) {
        int colonIdx = symbol.indexOf(':');
        if (colonIdx > 0) {
            String table = symbol.substring(0, colonIdx).trim();
            int slot = Integer.parseInt(symbol.substring(colonIdx + 1).trim());
            return ResourceId.ofRecord(table, new RID(new PageId(table, 0L), slot));
        }
        int slot = Math.abs(symbol.hashCode() % 10_000);
        return ResourceId.ofRecord("items_" + symbol, new RID(new PageId("items_" + symbol, 0L), slot));
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
}
