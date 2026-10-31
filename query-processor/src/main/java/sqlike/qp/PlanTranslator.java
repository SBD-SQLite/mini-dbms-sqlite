package sqlike.qp;

import java.util.List;
import java.util.Optional;
import sqlike.contract.IStorageManager;
import sqlike.shared.JoinAlgorithm;
import sqlike.shared.JoinSpec;
import sqlike.shared.JoinType;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.QueryPlan;
import sqlike.shared.ScanSpec;
import sqlike.shared.SortSpec;

/**
 * Concrete PlanTranslator implementation matching CLASS DIAGRAM FIX.
 * Translates Query Optimizer plan trees into execution Operator / CommandExecutor trees,
 * maintaining parent-child relationships and parameter specifications across all PlanNodeKinds.
 */
public class PlanTranslator {
    private final IStorageManager storage;
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
        // TODO(M1-MOCK): Store all QP2 dependencies and component stubs
        this.storage = storage;
        this.evaluator = evaluator;
        this.comparator = comparator;
        this.mutations = mutations;
        this.collector = collector;
        this.estimator = estimator;
        this.validator = validator != null ? validator : new PlanValidator();
    }

    public void validate(PreparedStatement statement) {
        // TODO(M1-MOCK): Delegate validation of prepared statement to validator
        if (statement == null) {
            throw new IllegalArgumentException("PreparedStatement cannot be null");
        }
        validator.validate(statement);
    }

    public void validate(QueryPlan plan) {
        // TODO(M1-MOCK): Overload to validate QueryPlan directly
        if (plan == null) {
            throw new IllegalArgumentException("QueryPlan cannot be null");
        }
        validator.validate(plan);
    }

    public Operator translateSelect(PlanNode root) {
        // TODO(M1-MOCK): Translate root query plan for SELECT and row-producing queries
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
        // TODO(M1-MOCK): Translate root query plan for DML and DDL commands
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
            default -> throw new IllegalArgumentException("Unsupported command kind: " + root.kind());
        };
    }

    private Operator translateRead(PlanNode node) {
        // TODO(M1-MOCK): Recursive bottom-up translation of read nodes
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
                yield buildIndexScan(node, spec);
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
            default -> throw new IllegalArgumentException("Unsupported read node kind: " + node.kind());
        };
    }

    private Operator buildScan(PlanNode node, ScanSpec spec) {
        // TODO(M1-MOCK): Construct scan operator with output schema
        Schema outputSchema = Schema.of(spec.tableName(), List.of());
        return new MockOperator(PlanNodeKind.SCAN, outputSchema, List.of());
    }

    private Operator buildIndexScan(PlanNode node, IndexScanSpec spec) {
        // TODO(M1-MOCK): Construct index scan operator with output schema
        Schema outputSchema = Schema.of(spec.tableName(), List.of());
        return new MockOperator(PlanNodeKind.INDEX_SCAN, outputSchema, List.of());
    }

    private Operator buildFilter(PlanNode node, FilterSpec spec) {
        // TODO(M1-MOCK): Construct filter operator over child operator
        Operator child = translateRead(node.children().get(0));
        return new MockOperator(PlanNodeKind.FILTER, child.schema(), List.of(child));
    }

    private Operator buildProject(PlanNode node, ProjectSpec spec) {
        // TODO(M1-MOCK): Construct project operator over child operator
        Operator child = translateRead(node.children().get(0));
        return new MockOperator(PlanNodeKind.PROJECT, child.schema(), List.of(child));
    }

    private Operator buildJoin(PlanNode node, JoinSpec spec) {
        // TODO(M1-MOCK): Construct join operator combining two children
        Operator leftChild = translateRead(node.children().get(0));
        Operator rightChild = translateRead(node.children().get(1));
        return new MockOperator(node.kind(), Schema.of(List.of()), List.of(leftChild, rightChild));
    }

    private Operator buildSort(PlanNode node, SortSpec spec) {
        // TODO(M1-MOCK): Construct sort operator over child operator
        Operator child = translateRead(node.children().get(0));
        return new MockOperator(PlanNodeKind.SORT, child.schema(), List.of(child));
    }

    private CommandExecutor buildInsert(PlanNode node, InsertSpec spec) {
        // TODO(M1-MOCK): Construct insert executor for target table
        return new MockCommandExecutor(PlanNodeKind.INSERT);
    }

    private CommandExecutor buildUpdate(PlanNode node, UpdateSpec spec) {
        // TODO(M1-MOCK): Construct update executor over source child operator if present
        if (!node.children().isEmpty()) {
            translateRead(node.children().get(0));
        }
        return new MockCommandExecutor(PlanNodeKind.UPDATE);
    }

    private CommandExecutor buildDelete(PlanNode node, DeleteSpec spec) {
        // TODO(M1-MOCK): Construct delete executor over source child operator if present
        if (!node.children().isEmpty()) {
            translateRead(node.children().get(0));
        }
        return new MockCommandExecutor(PlanNodeKind.DELETE);
    }

    private CommandExecutor buildCreateIndex(PlanNode node, CreateIndexSpec spec) {
        // TODO(M1-MOCK): Construct create index executor
        return new MockCommandExecutor(PlanNodeKind.CREATE_INDEX);
    }

    private boolean isCommandKind(PlanNodeKind kind) {
        return kind == PlanNodeKind.INSERT
                || kind == PlanNodeKind.UPDATE
                || kind == PlanNodeKind.DELETE
                || kind == PlanNodeKind.CREATE_INDEX;
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
