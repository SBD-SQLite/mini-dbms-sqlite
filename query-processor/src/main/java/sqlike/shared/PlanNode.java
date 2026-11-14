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
        Optional<DropTableSpec> dropTable) {
    public PlanNode {
        children = children == null ? List.of() : List.copyOf(children);
        outputColumns = outputColumns == null ? List.of() : List.copyOf(outputColumns);
        projection = projection == null ? List.of() : List.copyOf(projection);
        estimatedRows = estimatedRows == null ? Optional.empty() : estimatedRows;
        scan = scan == null ? Optional.empty() : scan;
        predicate = predicate == null ? Optional.empty() : predicate;
        join = join == null ? Optional.empty() : join;
        sort = sort == null ? Optional.empty() : sort;
        mutation = mutation == null ? Optional.empty() : mutation;
        createIndex = createIndex == null ? Optional.empty() : createIndex;
        limit = limit == null ? Optional.empty() : limit;
        createTable = createTable == null ? Optional.empty() : createTable;
        dropTable = dropTable == null ? Optional.empty() : dropTable;
    }

    public PlanNode(
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
            Optional<IndexMetadata> createIndex) {
        this(
                kind,
                children,
                outputColumns,
                estimatedRows,
                scan,
                predicate,
                projection,
                join,
                sort,
                mutation,
                createIndex,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
