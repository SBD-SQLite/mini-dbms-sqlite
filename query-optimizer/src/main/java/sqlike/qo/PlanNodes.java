package sqlike.qo;
import java.util.List;
import java.util.Optional;
import sqlike.shared.*;

/** Factory + validator for {@link PlanNode}. Every node built di sini so the agreed invariants hold: arity per kind, unused payload slots empty, SortSpec single column, INSERT one row, UPDATE one column*/
public final class PlanNodes {
    private PlanNodes() { }

    private static PlanNode node(PlanNodeKind kind, List<PlanNode> children, List<ColumnRef> projection,
                                 Optional<ScanSpec> scan, Optional<Expression> predicate, Optional<JoinSpec> join,
                                 Optional<SortSpec> sort, Optional<MutationSpec> mutation, Optional<IndexMetadata> idx) {
        return new PlanNode(kind, children, List.of(), Optional.empty(), scan, predicate, projection, join, sort, mutation, idx);
    }

    public static PlanNode scan(String table) {
        return node(PlanNodeKind.SCAN, List.of(), List.of(), Optional.of(new ScanSpec(table, Optional.empty(), Optional.empty())),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
    public static PlanNode indexScan(String table, String indexName, IndexCondition cond) {
        return node(PlanNodeKind.INDEX_SCAN, List.of(), List.of(), Optional.of(new ScanSpec(table, Optional.of(indexName), Optional.of(cond))),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
    public static PlanNode filter(PlanNode child, Expression predicate) {
        return node(PlanNodeKind.FILTER, List.of(child), List.of(), Optional.empty(), Optional.of(predicate),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
    public static PlanNode project(PlanNode child, List<ColumnRef> columns) {
        return node(PlanNodeKind.PROJECT, List.of(child), columns, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static PlanNode join(PlanNode left, PlanNode right, JoinSpec spec) {
        PlanNodeKind k = spec.joinType() == JoinType.CARTESIAN ? PlanNodeKind.CARTESIAN : PlanNodeKind.JOIN;
        return node(k, List.of(left, right), List.of(), Optional.empty(), Optional.empty(), Optional.of(spec),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
    public static PlanNode sort(PlanNode child, SortSpec spec) {
        return node(PlanNodeKind.SORT, List.of(child), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(spec), Optional.empty(), Optional.empty());
    }
    public static PlanNode insert(MutationSpec spec) {
        return node(PlanNodeKind.INSERT, List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(spec), Optional.empty());
    }
    public static PlanNode update(PlanNode child, MutationSpec spec) {
        return node(PlanNodeKind.UPDATE, List.of(child), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(spec), Optional.empty());
    }
    public static PlanNode delete(PlanNode child, MutationSpec spec) {
        return node(PlanNodeKind.DELETE, List.of(child), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(spec), Optional.empty());
    }
    public static PlanNode createIndex(IndexMetadata meta) {
        return node(PlanNodeKind.CREATE_INDEX, List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(meta));
    }

    public static void validate(PlanNode n) {
        int arity = switch (n.kind()) {
            case SCAN, INDEX_SCAN, INSERT, CREATE_INDEX -> 0;
            case FILTER, PROJECT, SORT, UPDATE, DELETE -> 1;
            case JOIN, CARTESIAN -> 2;
        };
        require(n.children().size() == arity, n, "expects " + arity + " child(ren)");
        boolean scan = n.kind() == PlanNodeKind.SCAN || n.kind() == PlanNodeKind.INDEX_SCAN;
        require(scan == n.scan().isPresent(), n, "scan payload only on SCAN/INDEX_SCAN");
        require((n.kind() == PlanNodeKind.FILTER) == n.predicate().isPresent(), n, "predicate only on FILTER");
        require((n.kind() == PlanNodeKind.PROJECT) == !n.projection().isEmpty(), n, "projection only on PROJECT");
        boolean join = n.kind() == PlanNodeKind.JOIN || n.kind() == PlanNodeKind.CARTESIAN;
        require(join == n.join().isPresent(), n, "join payload only on JOIN/CARTESIAN");
        require((n.kind() == PlanNodeKind.SORT) == n.sort().isPresent(), n, "sort payload only on SORT");
        boolean mut = n.kind() == PlanNodeKind.INSERT || n.kind() == PlanNodeKind.UPDATE || n.kind() == PlanNodeKind.DELETE;
        require(mut == n.mutation().isPresent(), n, "mutation payload only on INSERT/UPDATE/DELETE");
        require((n.kind() == PlanNodeKind.CREATE_INDEX) == n.createIndex().isPresent(), n, "createIndex payload only on CREATE_INDEX");
        if (n.kind() == PlanNodeKind.INDEX_SCAN)
            require(n.scan().get().indexName().isPresent() && n.scan().get().condition().isPresent(), n, "INDEX_SCAN needs index + condition");
        if (n.kind() == PlanNodeKind.SCAN)
            require(n.scan().get().indexName().isEmpty() && n.scan().get().condition().isEmpty(), n, "SCAN has no index/condition");
        if (n.kind() == PlanNodeKind.INSERT) require(!n.mutation().get().insertValues().isEmpty(), n, "INSERT needs one row of values");
        if (n.kind() == PlanNodeKind.UPDATE)
            require(n.mutation().get().updateColumn().isPresent() && n.mutation().get().updateExpression().isPresent(), n, "UPDATE needs one column + expression");
        n.children().forEach(PlanNodes::validate);
    }

    private static void require(boolean ok, PlanNode n, String msg) {
        if (!ok) throw QoErrors.invalidState("invalid plan node " + n.kind() + ": " + msg);
    }
}
