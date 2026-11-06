package sqlike.shared;

import java.util.List;
import java.util.Optional;

public record PlanNode(
        PlanNodeKind kind,
        List<PlanNode> children,
        List<ResultColumn> outputColumns,
        Optional<Double> estimatedRows,
        Optional<ScanSpec> scan,
        Optional<Expression> predicate,
        List<ColumnRef> projection,
        Optional<JoinSpec> join,
        Optional<SortSpec> sort,
        Optional<MutationSpec> mutation,
        Optional<IndexMetadata> createIndex,
        Optional<LimitSpec> limit,
        Optional<CreateTableSpec> createTable,
        Optional<DropTableSpec> dropTable
) {
    public PlanNode(PlanNodeKind kind, List<PlanNode> children, List<ResultColumn> outputColumns, Optional<Double> estimatedRows, Optional<ScanSpec> scan, Optional<Expression> predicate, List<ColumnRef> projection, Optional<JoinSpec> join, Optional<SortSpec> sort, Optional<MutationSpec> mutation, Optional<IndexMetadata> createIndex) {
        this(kind, children, outputColumns, estimatedRows, scan, predicate, projection, join, sort, mutation, createIndex, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
