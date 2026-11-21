package sqlike.ccm;

import static sqlike.ccm.Check.require;

import java.util.List;
import java.util.Optional;

import sqlike.shared.PageId;
import sqlike.shared.RID;
import sqlike.shared.ResourceId;
import sqlike.shared.ResourceScope;

/** Precedence graph, serial order, and resource matching of the serializability verifier (CC-09). */
public final class SerializabilityVerifierTest {

    private static final SerializabilityVerifier VERIFIER = new SerializabilityVerifier();

    public static void main(String[] args) throws Exception {
        Check check = new Check("CC5 SerializabilityVerifier");
        check.test("an acyclic schedule is serializable with an equivalent serial order", SerializabilityVerifierTest::acyclic);
        check.test("a cycle in the precedence graph is not serializable", SerializabilityVerifierTest::cycle);
        check.test("reads never conflict with reads", SerializabilityVerifierTest::readsDoNotConflict);
        check.test("table names are compared case-insensitively", SerializabilityVerifierTest::caseInsensitive);
        check.test("record and table resources of one table overlap, other records do not", SerializabilityVerifierTest::recordVersusTable);
        check.done();
    }

    private static ResourceId table(String name) {
        return new ResourceId(ResourceScope.TABLE, name, Optional.empty());
    }

    private static ResourceId record(String table, int slot) {
        return new ResourceId(ResourceScope.RECORD, table, Optional.of(new RID(new PageId(table, 0L), slot)));
    }

    private static ScheduleOperation r(long tx, ResourceId resource) {
        return new ScheduleOperation(tx, resource, false);
    }

    private static ScheduleOperation w(long tx, ResourceId resource) {
        return new ScheduleOperation(tx, resource, true);
    }

    private static void acyclic() {
        List<ScheduleOperation> schedule = List.of(r(1, table("a")), w(1, table("a")), r(2, table("a")), w(2, table("a")));
        require(VERIFIER.isConflictSerializable(schedule), "T1 then T2 is serializable");
        require(VERIFIER.serialOrder(schedule).equals(List.of(1L, 2L)), "the serial order is T1, T2");
    }

    private static void cycle() {
        List<ScheduleOperation> schedule = List.of(r(1, table("a")), w(2, table("a")), r(2, table("b")), w(1, table("b")));
        require(!VERIFIER.isConflictSerializable(schedule), "T1 before T2 on a and T2 before T1 on b is a cycle");
    }

    private static void readsDoNotConflict() {
        List<ScheduleOperation> schedule = List.of(r(1, table("a")), r(2, table("a")), r(2, table("b")), r(1, table("b")));
        require(VERIFIER.buildPrecedenceGraph(schedule).values().stream().allMatch(java.util.Set::isEmpty),
                "two readers add no edge");
    }

    private static void caseInsensitive() {
        List<ScheduleOperation> schedule = List.of(r(1, table("Items")), w(2, table("items")), r(2, table("Other")), w(1, table("other")));
        require(!VERIFIER.isConflictSerializable(schedule), "Items and items are one table, so the cycle must be found");
    }

    private static void recordVersusTable() {
        List<ScheduleOperation> tableVsRecord = List.of(r(1, table("a")), w(2, record("a", 1)), r(2, table("b")), w(1, table("b")));
        require(!VERIFIER.isConflictSerializable(tableVsRecord), "a record write conflicts with a table read");
        List<ScheduleOperation> otherRecords = List.of(r(1, record("a", 1)), w(2, record("a", 2)), r(2, record("b", 1)), w(1, record("b", 1)));
        require(VERIFIER.isConflictSerializable(otherRecords), "different records of one table do not conflict");
    }
}
