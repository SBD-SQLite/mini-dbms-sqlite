package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import sqlike.contract.IStorageManager;
import sqlike.qp.port.QpContracts.TableId;
import sqlike.shared.ColumnRef;
import sqlike.shared.ColumnSchema;
import sqlike.shared.JoinAlgorithm;
import sqlike.shared.JoinSpec;
import sqlike.shared.JoinType;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.QueryPlan;
import sqlike.shared.ResultColumn;
import sqlike.shared.ScanSpec;
import sqlike.shared.SortSpec;
import sqlike.shared.TableSchema;

/**
 * Concrete PlanTranslator implementation matching CLASS DIAGRAM FIX (Tab 4).
 * Translates Query Optimizer plan trees into execution Operator / CommandExecutor trees,
 * maintaining parent-child relationships and parameter specifications across all PlanNodeKinds.
 */
public class PlanTranslator {
    private final IStorageManager storage;
    private final sqlike.qp.port.QpContracts.IStorageManager qpStorage;
    private final IExpressionEvaluator evaluator;
    private final IValueComparator comparator;
    private final MutationCoordinator mutations;
    private final MutationTargetCollector collector;
    private final RowSizeEstimator estimator;
    private final PlanValidator validator;

    public PlanTranslator(
            IStorageManager storage,
            IExpressionEvaluator evaluator,
            IValueComparator comparator,
            MutationCoordinator mutations,
            MutationTargetCollector collector,
            RowSizeEstimator estimator,
            PlanValidator validator) {
        this(storage, null, evaluator, comparator, mutations, collector, estimator, validator);
    }

    public static PlanTranslator withQpStorage(
            sqlike.qp.port.QpContracts.IStorageManager storage,
            IExpressionEvaluator evaluator,
            IValueComparator comparator,
            MutationCoordinator mutations,
            MutationTargetCollector collector,
            RowSizeEstimator estimator,
            PlanValidator validator) {
        return new PlanTranslator(null, storage, evaluator, comparator, mutations, collector, estimator, validator);
    }

    private PlanTranslator(
            IStorageManager storage,
            sqlike.qp.port.QpContracts.IStorageManager qpStorage,
            IExpressionEvaluator evaluator,
            IValueComparator comparator,
            MutationCoordinator mutations,
            MutationTargetCollector collector,
            RowSizeEstimator estimator,
            PlanValidator validator) {
        this.storage = storage;
        this.qpStorage = qpStorage;
        this.evaluator = evaluator != null ? evaluator : new ExpressionEvaluator(comparator != null ? comparator : new ValueComparator());
        this.comparator = comparator != null ? comparator : new ValueComparator();
        this.mutations = mutations;
        this.collector = collector != null ? collector : new MutationTargetCollector(storage);
        this.estimator = estimator != null ? estimator : new RowSizeEstimator();
        this.validator = validator != null ? validator : new PlanValidator();
        if (this.evaluator instanceof ExpressionEvaluator ee) {
            ee.setPlanTranslator(this);
        }
    }

    public void validate(PreparedStatement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("PreparedStatement cannot be null");
        }
        validator.validate(statement);
    }

    public void validate(QueryPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("QueryPlan cannot be null");
        }
        validator.validate(plan);
    }

    public Operator translateSelect(PlanNode root) {
        if (root == null) {
            throw new IllegalArgumentException("PlanNode root cannot be null");
        }

        validator.validate(root);

        if (isCommandKind(root.kind())) {
            throw new IllegalArgumentException(
                    "Node kind " + root.kind() + " is a mutation/command node; use translateCommand instead");
        }

        return translateRead(root);
    }

    public CommandExecutor translateCommand(PlanNode root) {
        if (root == null) {
            throw new IllegalArgumentException("PlanNode root cannot be null");
        }

        validator.validate(root);

        if (!isCommandKind(root.kind())) {
            throw new IllegalArgumentException(
                    "Node kind " + root.kind() + " is a query/read node; use translateSelect instead");
        }

        return switch (root.kind()) {
            case INSERT -> {
                InsertSpec spec = root.mutation()
                        .map(InsertSpec::fromMutationSpec)
                        .orElseThrow(() -> new IllegalArgumentException("INSERT node missing MutationSpec"));
                yield buildInsert(root, spec);
            }
            case UPDATE -> {
                UpdateSpec spec = root.mutation()
                        .map(UpdateSpec::fromMutationSpec)
                        .orElseThrow(() -> new IllegalArgumentException("UPDATE node missing MutationSpec"));
                yield buildUpdate(root, spec);
            }
            case DELETE -> {
                DeleteSpec spec = root.mutation()
                        .map(DeleteSpec::fromMutationSpec)
                        .or(() -> root.scan().map(s -> new DeleteSpec(s.tableName())))
                        .orElseThrow(() -> new IllegalArgumentException("DELETE node missing target table"));
                yield buildDelete(root, spec);
            }
            case CREATE_INDEX -> {
                CreateIndexSpec spec = root.createIndex()
                        .map(CreateIndexSpec::new)
                        .orElseThrow(() -> new IllegalArgumentException("CREATE_INDEX node missing IndexMetadata"));
                yield buildCreateIndex(root, spec);
            }
            case CREATE_TABLE -> {
                var spec = root.createTable()
                        .orElseThrow(() -> new IllegalArgumentException("CREATE_TABLE node missing CreateTableSpec"));
                yield buildCreateTable(root, spec);
            }
            case DROP_TABLE -> {
                var spec = root.dropTable()
                        .orElseThrow(() -> new IllegalArgumentException("DROP_TABLE node missing DropTableSpec"));
                yield buildDropTable(root, spec);
            }
            default -> throw new IllegalArgumentException("Unsupported command kind: " + root.kind());
        };
    }

    private Operator translateRead(PlanNode node) {
        if (node == null) {
            throw new IllegalArgumentException("PlanNode cannot be null in translateRead");
        }

        return switch (node.kind()) {
            case SCAN -> {
                ScanSpec spec = node.scan()
                        .orElseThrow(() -> new IllegalArgumentException("SCAN node missing ScanSpec"));
                yield buildScan(node, spec);
            }
            case INDEX_SCAN -> {
                ScanSpec scan = node.scan()
                        .orElseThrow(() -> new IllegalArgumentException("INDEX_SCAN node missing ScanSpec"));
                IndexScanSpec spec = IndexScanSpec.fromScanSpec(scan);
                yield buildIndexScan(node, spec, scan.alias());
            }
            case FILTER -> {
                var expr = node.predicate()
                        .orElseThrow(() -> new IllegalArgumentException("FILTER node missing predicate expression"));
                FilterSpec spec = new FilterSpec(expr);
                yield buildFilter(node, spec);
            }
            case PROJECT -> {
                ProjectSpec spec = new ProjectSpec(node.projection());
                yield buildProject(node, spec);
            }
            case JOIN -> {
                JoinSpec spec = node.join()
                        .orElseThrow(() -> new IllegalArgumentException("JOIN node missing JoinSpec"));
                yield buildJoin(node, spec);
            }
            case CARTESIAN -> {
                JoinSpec spec = node.join()
                        .orElse(new JoinSpec(JoinType.CARTESIAN, Optional.empty(), JoinAlgorithm.BLOCK_NESTED_LOOP, List.of()));
                yield buildJoin(node, spec);
            }
            case SORT -> {
                SortSpec spec = node.sort()
                        .orElseThrow(() -> new IllegalArgumentException("SORT node missing SortSpec"));
                yield buildSort(node, spec);
            }
            case LIMIT -> {
                var spec = node.limit()
                        .orElseThrow(() -> new IllegalArgumentException("LIMIT node missing LimitSpec"));
                yield buildLimit(node, spec);
            }
            default -> throw new IllegalArgumentException("Unsupported read node kind: " + node.kind());
        };
    }

    private Operator buildScan(PlanNode node, ScanSpec spec) {
        String qualifier = spec.alias().filter(a -> !a.isBlank()).orElse(spec.tableName());
        if (!hasStorage()) {
            Schema outputSchema = Schema.of(qualifier, List.of());
            return new MockOperator(PlanNodeKind.SCAN, outputSchema, List.of());
        }
        Schema baseSchema = resolveTableSchema(spec.tableName(), node);
        Schema outputSchema = Schema.of(qualifier, baseSchema.columns());
        if (qpStorage != null) {
            return new TableScanOperator(qpStorage, spec, outputSchema);
        }
        return new TableScanOperator(storage, spec, outputSchema);
    }

    private Operator buildIndexScan(PlanNode node, IndexScanSpec spec, Optional<String> alias) {
        String qualifier = alias != null && alias.isPresent() && !alias.get().isBlank()
                ? alias.get()
                : spec.tableName();
        if (!hasStorage()) {
            Schema outputSchema = Schema.of(qualifier, List.of());
            return new MockOperator(PlanNodeKind.INDEX_SCAN, outputSchema, List.of());
        }
        Schema baseSchema = resolveTableSchema(spec.tableName(), node);
        Schema outputSchema = Schema.of(qualifier, baseSchema.columns());
        if (qpStorage != null) {
            return new IndexScanOperator(qpStorage, spec, outputSchema);
        }
        return new IndexScanOperator(storage, spec, outputSchema);
    }

    private Operator buildFilter(PlanNode node, FilterSpec spec) {
        Operator child = translateRead(node.children().get(0));
        if (!hasStorage()) {
            return new MockOperator(PlanNodeKind.FILTER, child.schema(), List.of(child));
        }
        return new FilterOperator(child, spec, evaluator, child.schema());
    }

    private Operator buildProject(PlanNode node, ProjectSpec spec) {
        Operator child = translateRead(node.children().get(0));
        if (!hasStorage()) {
            return new MockOperator(PlanNodeKind.PROJECT, child.schema(), List.of(child));
        }
        if (spec.columns().size() == 1
                && spec.columns().get(0).columnName().equals("*")
                && spec.columns().get(0).tableName().isEmpty()) {
            return child;
        }
        Schema childSchema = child.schema();
        List<ColumnRef> expandedRefs = new ArrayList<>();
        for (ColumnRef ref : spec.columns()) {
            if (ref.columnName().equals("*")) {
                if (ref.tableName().isEmpty()) {
                    for (int i = 0; i < childSchema.columnCount(); i++) {
                        ColumnSchema col = childSchema.getColumn(i);
                        Optional<String> owner = childSchema.columnTablesAt(i).stream().findFirst();
                        expandedRefs.add(new ColumnRef(owner, col.name()));
                    }
                } else {
                    String targetTable = ref.tableName().get().trim().toLowerCase(Locale.ROOT);
                    for (int i = 0; i < childSchema.columnCount(); i++) {
                        if (childSchema.columnTablesAt(i).contains(targetTable)) {
                            expandedRefs.add(new ColumnRef(ref.tableName(), childSchema.getColumn(i).name()));
                        }
                    }
                }
            } else {
                expandedRefs.add(ref);
            }
        }
        ProjectSpec effectiveSpec = new ProjectSpec(expandedRefs);
        List<ColumnSchema> projectedCols = new ArrayList<>(expandedRefs.size());
        List<Set<String>> projectedOwners = new ArrayList<>(expandedRefs.size());
        for (ColumnRef ref : expandedRefs) {
            int offset = childSchema.findColumnOffset(ref);
            projectedCols.add(childSchema.getColumn(offset));
            projectedOwners.add(childSchema.columnTablesAt(offset));
        }
        Schema outputSchema = Schema.withColumnTables(projectedCols, childSchema.tableName(), projectedOwners);
        return new ProjectOperator(child, effectiveSpec, outputSchema);
    }

    private Operator buildJoin(PlanNode node, JoinSpec spec) {
        Operator leftChild = translateRead(node.children().get(0));
        Operator rightChild = translateRead(node.children().get(1));
        if (!hasStorage()) {
            return new MockOperator(node.kind(), Schema.of(List.of()), List.of(leftChild, rightChild));
        }
        List<ColumnSchema> leftCols = leftChild.schema().getColumns();
        List<ColumnSchema> rightCols = rightChild.schema().getColumns();
        List<String> common = spec.commonColumns() != null ? spec.commonColumns() : List.of();
        if (spec.joinType() == JoinType.NATURAL && common.isEmpty()) {
            List<String> inferred = new ArrayList<>();
            for (ColumnSchema lc : leftCols) {
                for (ColumnSchema rc : rightCols) {
                    if (lc.name().equalsIgnoreCase(rc.name())) {
                        inferred.add(lc.name());
                        break;
                    }
                }
            }
            common = List.copyOf(inferred);
        }
        JoinSpec effectiveSpec = new JoinSpec(
                spec.joinType() != null ? spec.joinType() : JoinType.CARTESIAN,
                spec.condition(),
                spec.algorithm() != null ? spec.algorithm() : JoinAlgorithm.BLOCK_NESTED_LOOP,
                common);

        List<ColumnSchema> outputCols = new ArrayList<>();
        List<Integer> mapping = new ArrayList<>();
        for (int i = 0; i < leftCols.size(); i++) {
            outputCols.add(leftCols.get(i));
            mapping.add(i);
        }
        for (int j = 0; j < rightCols.size(); j++) {
            ColumnSchema rc = rightCols.get(j);
            boolean duplicateNatural = effectiveSpec.joinType() == JoinType.NATURAL
                    && common.stream().anyMatch(c -> c.equalsIgnoreCase(rc.name()));
            if (!duplicateNatural) {
                outputCols.add(rc);
                mapping.add(leftCols.size() + j);
            }
        }
        Schema baseOutputSchema = Schema.of(outputCols);
        JoinLayout layout = new JoinLayout(leftChild.schema(), rightChild.schema(), baseOutputSchema, mapping);
        return new BlockNestedLoopJoinOperator(
                leftChild,
                rightChild,
                effectiveSpec,
                layout,
                evaluator,
                comparator,
                estimator,
                layout.getOutputSchema());
    }

    private Operator buildSort(PlanNode node, SortSpec spec) {
        Operator child = translateRead(node.children().get(0));
        if (!hasStorage()) {
            return new MockOperator(PlanNodeKind.SORT, child.schema(), List.of(child));
        }
        RowComparator rowComp = new RowComparator(child.schema(), spec.column(), spec.direction(), comparator);
        return new SortOperator(child, spec, rowComp, child.schema());
    }

    private Operator buildLimit(PlanNode node, sqlike.shared.LimitSpec spec) {
        Operator child = translateRead(node.children().get(0));
        if (!hasStorage()) {
            return new MockOperator(PlanNodeKind.LIMIT, child.schema(), List.of(child));
        }
        return new LimitOperator(child, spec, child.schema());
    }

    private CommandExecutor buildInsert(PlanNode node, InsertSpec spec) {
        if (!hasStorage() || mutations == null) {
            return new MockCommandExecutor(PlanNodeKind.INSERT);
        }
        return new InsertExecutor(spec, mutations);
    }

    private CommandExecutor buildUpdate(PlanNode node, UpdateSpec spec) {
        Operator source = !node.children().isEmpty() ? translateRead(node.children().get(0)) : null;
        if (!hasStorage() || source == null || collector == null || evaluator == null || mutations == null) {
            return new MockCommandExecutor(PlanNodeKind.UPDATE);
        }
        Schema tableSchema = resolveTableSchema(spec.tableName(), node);
        return new UpdateExecutor(source, spec, tableSchema, collector, evaluator, mutations);
    }

    private CommandExecutor buildDelete(PlanNode node, DeleteSpec spec) {
        Operator source = !node.children().isEmpty() ? translateRead(node.children().get(0)) : null;
        if (!hasStorage() || source == null || collector == null || mutations == null) {
            return new MockCommandExecutor(PlanNodeKind.DELETE);
        }
        return new DeleteExecutor(source, spec, collector, mutations);
    }

    private CommandExecutor buildCreateIndex(PlanNode node, CreateIndexSpec spec) {
        if (!hasStorage()) {
            return new MockCommandExecutor(PlanNodeKind.CREATE_INDEX);
        }
        if (qpStorage != null) {
            return new CreateIndexExecutor(spec, qpStorage);
        }
        return new CreateIndexExecutor(spec, storage);
    }

    private CommandExecutor buildCreateTable(PlanNode node, sqlike.shared.CreateTableSpec spec) {
        if (!hasStorage()) {
            return new MockCommandExecutor(PlanNodeKind.CREATE_TABLE);
        }
        if (qpStorage != null) {
            return new CreateTableExecutor(spec, qpStorage, null);
        }
        return new CreateTableExecutor(spec, storage);
    }

    private CommandExecutor buildDropTable(PlanNode node, sqlike.shared.DropTableSpec spec) {
        if (!hasStorage()) {
            return new MockCommandExecutor(PlanNodeKind.DROP_TABLE);
        }
        if (qpStorage != null) {
            return new DropTableExecutor(spec, qpStorage, null);
        }
        return new DropTableExecutor(spec, storage);
    }

    private boolean hasStorage() {
        return storage != null || qpStorage != null;
    }

    private Schema resolveTableSchema(String tableName, PlanNode node) {
        if (qpStorage != null) {
            return qpStorage.getSchema(new TableId(tableName));
        }
        if (storage != null) {
            TableSchema ts = storage.getSchema(tableName);
            if (ts != null) {
                return Schema.fromTableSchema(ts);
            }
        }
        if (node != null && node.outputColumns() != null && !node.outputColumns().isEmpty()) {
            List<ColumnSchema> cols = new ArrayList<>();
            for (ResultColumn rc : node.outputColumns()) {
                cols.add(new ColumnSchema(rc.name(), rc.dataType(), Optional.empty()));
            }
            return Schema.of(tableName, cols);
        }
        return Schema.of(tableName, List.of());
    }

    private boolean isCommandKind(PlanNodeKind kind) {
        return kind == PlanNodeKind.INSERT
                || kind == PlanNodeKind.UPDATE
                || kind == PlanNodeKind.DELETE
                || kind == PlanNodeKind.CREATE_INDEX
                || kind == PlanNodeKind.CREATE_TABLE
                || kind == PlanNodeKind.DROP_TABLE;
    }

    // Getters for dependency verification in testing
    public IStorageManager getStorage() { return storage; }
    public IExpressionEvaluator getEvaluator() { return evaluator; }
    public IValueComparator getComparator() { return comparator; }
    public MutationCoordinator getMutations() { return mutations; }
    public MutationTargetCollector getCollector() { return collector; }
    public RowSizeEstimator getEstimator() { return estimator; }
    public PlanValidator getValidator() { return validator; }
}
