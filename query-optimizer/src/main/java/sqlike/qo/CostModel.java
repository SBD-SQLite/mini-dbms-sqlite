package sqlike.qo;

import sqlike.contract.IStorageManager;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.IndexMetadata;
import sqlike.shared.JoinSpec;
import sqlike.shared.MutationSpec;
import sqlike.shared.PlanNode;
import sqlike.shared.SubquerySpec;

import java.util.function.Supplier;
import sqlike.shared.TableStatistics;

/** Estimates disk I/O time in milliseconds using seek and block-transfer costs. */
public final class CostModel {
    private static final double SEEK_TIME_MS = 4.0;
    private static final double TRANSFER_TIME_MS = 0.1;
    // Default assumption: one outer block, one input block, and one output block.
    private static final int DEFAULT_BUFFER_BLOCKS = 3;

    private final IStorageManager storage;
    private final int bufferBlocks;
    private final CardinalityEstimator estimator;

    public CostModel(IStorageManager storage) {
        this(storage, DEFAULT_BUFFER_BLOCKS);
    }

    public CostModel(IStorageManager storage, int bufferBlocks) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        if (bufferBlocks < 3) throw new IllegalArgumentException("bufferBlocks must be at least 3");
        this.storage = storage;
        this.bufferBlocks = bufferBlocks;
        this.estimator = new CardinalityEstimator(storage);
    }

    /** Returns the sum of each node's local I/O cost and its child-plan costs. */
    public double getCost(PlanNode root) {
        if (root == null) throw QoErrors.invalidValue("plan root is null", 0);
        return withCatalogCache(() -> costNode(root));
    }

    <T> T withCatalogCache(Supplier<T> action) {
        return estimator.withCatalogCache(action);
    }

    private double costNode(PlanNode node) {
        double childCost = node.children().stream().mapToDouble(this::costNode).sum();
        double subqueryCost = expressionSubqueryCost(node);
        return childCost + subqueryCost + switch (node.kind()) {
            case SCAN -> costScan(node);
            case INDEX_SCAN -> costIndexScan(node);
            case FILTER -> costFilter(node);
            case PROJECT -> costProject(node);
            case JOIN, CARTESIAN -> costJoinBNLJ(node);
            case SORT -> costSort(node);
            case INSERT -> costInsert(node);
            case UPDATE -> costUpdate(node);
            case DELETE -> costDelete(node);
            case CREATE_INDEX -> costCreateIndex(node);
            // These operators are passed through or pipelined; they add no modeled page I/O.
            case LIMIT, CREATE_TABLE, DROP_TABLE -> 0.0;
        };
    }

    private double expressionSubqueryCost(PlanNode node) {
        double evaluations = estimatedExpressionEvaluations(node);
        double cost = 0.0;
        if (node.predicate().isPresent()) cost += expressionSubqueryCost(node.predicate().get(), evaluations);
        if (node.join().flatMap(JoinSpec::condition).isPresent())
            cost += expressionSubqueryCost(node.join().flatMap(JoinSpec::condition).orElseThrow(), evaluations);
        if (node.mutation().flatMap(MutationSpec::updateExpression).isPresent())
            cost += expressionSubqueryCost(node.mutation().flatMap(MutationSpec::updateExpression).orElseThrow(), evaluations);
        return cost;
    }

    private double expressionSubqueryCost(Expression expression, double evaluations) {
        double cost = 0.0;
        if (expression.kind() == ExpressionKind.SUBQUERY) {
            SubquerySpec subquery = expression.subquery().orElseThrow();
            double executions = subquery.correlated() ? evaluations : 1.0;
            // Model a full evaluation; short-circuit behavior (notably EXISTS) is unknown.
            cost += executions * costNode(subquery.root());
        }
        for (Expression operand : expression.operands())
            cost += expressionSubqueryCost(operand, evaluations);
        return cost;
    }

    private double estimatedExpressionEvaluations(PlanNode node) {
        return switch (node.kind()) {
            case FILTER, UPDATE, DELETE -> node.children().isEmpty() ? 0.0 : estimator.estimateRows(node.children().get(0));
            case JOIN, CARTESIAN -> {
                if (node.children().size() != 2) yield 0.0;
                yield (double) estimator.estimateRows(node.children().get(0))
                        * estimator.estimateRows(node.children().get(1));
            }
            default -> 1.0;
        };
    }

    private double costScan(PlanNode node) {
        long blocks = estimator.estimateBlocks(node);
        return sequentialReadCost(blocks);
    }

    private double costIndexScan(PlanNode node) {
        long dataBlocks = estimator.estimateBlocks(node);
        // The contract has no index height/page count. Model one index-page access,
        // then a random heap-page access for each distinct qualifying data page.
        return SEEK_TIME_MS + TRANSFER_TIME_MS
                + dataBlocks * (SEEK_TIME_MS + TRANSFER_TIME_MS);
    }

    private double costFilter(PlanNode node) {
        // Selection is pipelined and CPU comparison cost is outside this disk-I/O model.
        return 0.0;
    }

    private double costProject(PlanNode node) {
        // Projection is pipelined and adds no disk I/O unless output is materialized.
        return 0.0;
    }

    private double costJoinBNLJ(PlanNode node) {
        if (node.children().size() != 2)
            throw QoErrors.invalidState("BNLJ cost requires two input plans");

        long outerBlocks = estimator.estimateBlocks(node.children().get(0));
        long innerBlocks = estimator.estimateBlocks(node.children().get(1));
        if (outerBlocks == 0 || innerBlocks == 0) return 0.0;

        int outerCapacity = bufferBlocks - 2;
        long batches = ceilDiv(outerBlocks, outerCapacity);
        long innerRescans = Math.max(0, batches - 1);

        // Classic BNLJ charges two seeks per outer batch: one to read that batch,
        // one to rescan the inner relation. Child costs already include the first
        // seek and transfer pass for each input, so add both seeks and the inner
        // transfers only for subsequent batches. Temporary-result writes remain
        // outside this model.
        // QP's BlockNestedLoopJoinOperator is still an M1 mock (batch filling,
        // inner reopening, and matching are stubs), so it does not yet establish
        // whether join inputs are spooled/materialized. This estimate assumes
        // re-readable inputs; revisit it when the QP BNLJ execution is implemented.
        return innerRescans * (2.0 * SEEK_TIME_MS + innerBlocks * TRANSFER_TIME_MS);
    }

    private double costSort(PlanNode node) {
        long blocks = estimator.estimateBlocks(node.children().get(0));
        if (blocks <= bufferBlocks) return 0.0;

        int fanIn = bufferBlocks - 1;
        long runs = ceilDiv(blocks, bufferBlocks);
        long mergePasses = 0;
        while (runs > 1) {
            runs = ceilDiv(runs, fanIn);
            mergePasses++;
        }

        // Course formula assumes one buffer block per run (b_b = 1).
        long totalSeeks = 2 * ceilDiv(blocks, bufferBlocks)
                + blocks * (2 * mergePasses - 1);
        long totalTransfers = blocks * (2 * mergePasses + 1);

        // The child plan already charges the initial sequential input read. Remove
        // its one seek and b transfers from the full external-sort formula so that
        // SORT(SCAN ...) matches the textbook total without double-counting input.
        long sortSeeks = Math.max(0, totalSeeks - 1);
        long sortTransfers = Math.max(0, totalTransfers - blocks);
        return sortSeeks * SEEK_TIME_MS + sortTransfers * TRANSFER_TIME_MS;
    }

    private double costInsert(PlanNode node) {
        // The interface does not expose the destination page. Assume one page access
        // to insert and persist the row.
        return SEEK_TIME_MS + TRANSFER_TIME_MS;
    }

    private double costUpdate(PlanNode node) {
        return costModifiedPages(node);
    }

    private double costDelete(PlanNode node) {
        return costModifiedPages(node);
    }

    private double costModifiedPages(PlanNode node) {
        if (node.children().isEmpty()) return 0.0;
        long modifiedBlocks = estimator.estimateBlocks(node.children().get(0));
        if (modifiedBlocks == 0) return 0.0;
        // Child costs include finding/reading qualifying rows. Charge one seek and
        // one transfer per dirty page to persist the modifications.
        return SEEK_TIME_MS + modifiedBlocks * TRANSFER_TIME_MS;
    }

    private double costCreateIndex(PlanNode node) {
        IndexMetadata index = node.createIndex().orElseThrow();
        TableStatistics stats = storage.getStatistics(actualTableName(index.tableName()));
        long tableBlocks = stats.blockCount();
        long indexBlocks = stats.recordCount() == 0 ? 0
                : ceilDiv(stats.recordCount(), Math.max(1, stats.blockingFactor()));

        // Approximate index construction as a sequential table read plus sequential
        // index-page writes. Index fanout/height and index-page occupancy are unknown.
        return sequentialReadCost(tableBlocks)
                + (indexBlocks == 0 ? 0 : SEEK_TIME_MS + indexBlocks * TRANSFER_TIME_MS);
    }

    private double sequentialReadCost(long blocks) {
        return blocks <= 0 ? 0.0 : SEEK_TIME_MS + blocks * TRANSFER_TIME_MS;
    }

    private String actualTableName(String table) {
        return storage.listTables().stream().filter(name -> name.equalsIgnoreCase(table)).findFirst()
                .orElseThrow(() -> QoErrors.semantic("unknown table '" + table + "'"));
    }

    private static long ceilDiv(long numerator, long denominator) {
        return numerator <= 0 ? 0 : 1 + (numerator - 1) / denominator;
    }
}
