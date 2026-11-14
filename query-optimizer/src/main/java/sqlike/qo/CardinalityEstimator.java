package sqlike.qo;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

public final class CardinalityEstimator {
    private static final double BLOCK_SIZE_BYTES = 4096.0;

    private final IStorageManager storage;
    private final ThreadLocal<CatalogCache> activeCache = new ThreadLocal<>();

    public CardinalityEstimator(IStorageManager storage) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        this.storage = storage;
    }

    public long estimateRows(PlanNode node) {
        if (node == null) throw QoErrors.invalidValue("plan node is null", 0);
        return withCatalogCache(() -> estimateRowsCached(node));
    }

    private long estimateRowsCached(PlanNode node) {
        return switch (node.kind()) {
            case SCAN -> stats(node.scan().orElseThrow().tableName()).recordCount();
            case INDEX_SCAN -> clampRows(estimateRowsForScan(node.scan().orElseThrow().tableName()) *
                    indexSelectivity(node.scan().orElseThrow()));
            case FILTER -> clampRows(estimateRows(node.children().get(0)) *
                    selectivity(node.predicate().orElseThrow(), node.children().get(0)));
            case JOIN, CARTESIAN -> joinRows(node);
            case PROJECT, SORT, UPDATE, DELETE -> node.children().isEmpty() ? 0 : estimateRows(node.children().get(0));
            case INSERT -> 1;
            case CREATE_INDEX -> 0;
            case LIMIT -> node.children().isEmpty() ? 0
                    : Math.min(estimateRows(node.children().get(0)), node.limit().orElseThrow().count());
            case CREATE_TABLE, DROP_TABLE -> 0;
        };
    }

    public long estimateBlocks(PlanNode node) {
        if (node == null) throw QoErrors.invalidValue("plan node is null", 0);
        return withCatalogCache(() -> estimateBlocksCached(node));
    }

    private long estimateBlocksCached(PlanNode node) {
        if (node.kind() == PlanNodeKind.SCAN)
            return stats(node.scan().orElseThrow().tableName()).blockCount();
        if (node.kind() == PlanNodeKind.INDEX_SCAN) {
            String table = node.scan().orElseThrow().tableName();
            long tableBlocks = stats(table).blockCount();
            long qualifyingRows = estimateRows(node);
            if (tableBlocks <= 0 || qualifyingRows <= 0) return 0;

            // SM reports its secondary indexes as unclustered. Assume qualifying
            // RIDs are uniformly scattered across table pages: expected distinct
            // pages = b * (1 - (1 - 1/b)^n).
            double expectedPages = -((double) tableBlocks) *
                    Math.expm1(qualifyingRows * Math.log1p(-1.0 / tableBlocks));
            long roundedPages = (long) Math.ceil(expectedPages);
            return Math.min(tableBlocks, Math.max(1, roundedPages));
        }
        long rows = estimateRows(node);
        if (rows == 0) return 0;
        if (node.children().isEmpty()) return 1;

        double tupleSize = estimatedTupleSize(node);
        int blockingFactor = tupleSize > 0
                ? Math.max(1, (int) Math.floor(BLOCK_SIZE_BYTES / tupleSize))
                : Math.max(1, firstStats(node).blockingFactor());
        return 1 + (rows - 1) / blockingFactor;
    }

    public double selectivity(Expression condition, PlanNode child) {
        if (condition == null || child == null)
            throw QoErrors.invalidValue("condition and child plan are required", 0);
        return withCatalogCache(() -> selectivityCached(condition, child));
    }

    private double selectivityCached(Expression condition, PlanNode child) {
        if (condition.kind() == ExpressionKind.SUBQUERY)
            return subquerySelectivity(condition, child);
        if (condition.kind() == ExpressionKind.BINARY && condition.operator().orElse(null) == ExpressionOperator.AND && condition.operands().size() == 2)
            return clamp(selectivity(condition.operands().get(0), child) * selectivity(condition.operands().get(1), child));
        if (condition.kind() == ExpressionKind.BINARY) {
            ExpressionOperator op = condition.operator().orElse(null);
            if (condition.operands().size() == 2 && isComparison(op)) {
                Expression left = condition.operands().get(0), right = condition.operands().get(1);
                if (left.kind() == ExpressionKind.COLUMN && right.kind() == ExpressionKind.LITERAL)
                    return comparisonSelectivity(left.column().orElseThrow(), op, child);
                if (right.kind() == ExpressionKind.COLUMN && left.kind() == ExpressionKind.LITERAL)
                    return comparisonSelectivity(right.column().orElseThrow(), reverse(op), child);
                if (left.kind() == ExpressionKind.COLUMN && right.kind() == ExpressionKind.COLUMN) {
                    ColumnRef leftColumn = left.column().orElseThrow();
                    ColumnRef rightColumn = right.column().orElseThrow();
                    if (!hasQualifier(child, leftColumn) || !hasQualifier(child, rightColumn)) return 0.5;
                    return op == ExpressionOperator.EQ
                            ? 1.0 / Math.max(distinct(leftColumn, child), distinct(rightColumn, child))
                            : (op == ExpressionOperator.NE ? 0.9 : 1.0 / 3.0);
                }
            }
        }
        // TODO(QO3): Estimate IN/EXISTS/scalar-subquery predicates from SubquerySpec.root instead of this fallback.
        return 0.5;
    }

    private double subquerySelectivity(Expression expression, PlanNode input) {
        SubquerySpec spec = expression.subquery().orElseThrow();
        long subqueryRows = estimateRows(spec.root());
        if (spec.type() == SubqueryType.EXISTS) {
            if (subqueryRows == 0) return 0.0;
            return spec.correlated() ? 0.5 : 1.0;
        }
        if (spec.type() != SubqueryType.IN || spec.correlated() || expression.operands().isEmpty()) return 0.5;

        Expression probe = expression.operands().get(0);
        if (probe.kind() != ExpressionKind.COLUMN) return 0.5;
        ColumnRef probeColumn = probe.column().orElseThrow();
        if (!hasQualifier(input, probeColumn)) return 0.5;
        long inputDistinct = Math.max(1, distinct(probeColumn, input));
        if (subqueryRows == 0) return 0.0;

        ResultColumn output = spec.root().outputColumns().get(0);
        long resultDistinct = output.source().filter(source -> hasQualifier(spec.root(), source))
                .map(source -> distinct(source, spec.root())).orElse(subqueryRows);
        resultDistinct = Math.min(subqueryRows, Math.max(1, resultDistinct));
        return clamp(Math.min(inputDistinct, resultDistinct) / (double) inputDistinct);
    }

    private long joinRows(PlanNode node) {
        long leftRows = estimateRows(node.children().get(0));
        long rightRows = estimateRows(node.children().get(1));
        JoinSpec join = node.join().orElseThrow();
        if (join.joinType() == JoinType.CARTESIAN || node.kind() == PlanNodeKind.CARTESIAN)
            return safeMultiply(leftRows, rightRows);
        if (join.joinType() == JoinType.NATURAL && !join.commonColumns().isEmpty()) {
            double selectivity = 1.0;
            for (String name : join.commonColumns()) {
                ColumnRef l = findColumn(node.children().get(0), name);
                ColumnRef r = findColumn(node.children().get(1), name);
                selectivity /= Math.max(distinct(l, node.children().get(0)), distinct(r, node.children().get(1)));
            }
            return clampRows(leftRows * (double) rightRows * selectivity);
        }
        if (join.condition().isPresent()) return clampRows(leftRows * (double) rightRows * selectivity(join.condition().get(), node));
        return safeMultiply(leftRows, rightRows);
    }

    private double comparisonSelectivity(ColumnRef column, ExpressionOperator op, PlanNode scope) {
        if (!hasQualifier(scope, column)) return 0.5;
        long distinct = Math.max(1, distinct(column, scope));
        return switch (op) {
            case EQ -> 1.0 / distinct;
            case NE -> 1.0 - 1.0 / distinct;
            // SM does not expose min/max or histograms. Follow the course's n_r/2
            // range heuristic (selectivity 0.5) until richer statistics are agreed.
            case GT, GE, LT, LE -> 0.5;
            default -> 0.5;
        };
    }

    private double indexSelectivity(ScanSpec scan) {
        IndexCondition condition = scan.condition().orElseThrow();
        String table = scan.tableName();
        String indexName = scan.indexName().orElseThrow();
        String columnName = storage.getIndexes(actualTableName(table)).stream()
                .filter(index -> index.indexName().equalsIgnoreCase(indexName))
                .map(IndexMetadata::columnName).findFirst().orElse("");
        return switch (condition.operator()) {
            case EQ -> 1.0 / Math.max(1, distinct(table, columnName));
            // Same n_r/2 range heuristic used for sequential filters.
            case GT, GE, LT, LE, BETWEEN -> 0.5;
        };
    }

    private long estimateRowsForScan(String table) { return stats(table).recordCount(); }

    private long distinct(ColumnRef column, PlanNode scope) {
        String qualifier = column.tableName().orElseThrow(() -> QoErrors.semantic("unqualified column in cardinality estimate"));
        String tableName = tableForQualifier(scope, qualifier);
        return Math.max(1, distinct(tableName, column.columnName()));
    }

    private boolean hasQualifier(PlanNode scope, ColumnRef column) {
        return column.tableName().map(qualifier -> hasQualifier(scope, qualifier)).orElse(false);
    }

    private boolean hasQualifier(PlanNode node, String qualifier) {
        if (node.scan().isPresent()) {
            ScanSpec scan = node.scan().get();
            if (scan.tableName().equalsIgnoreCase(qualifier)
                    || scan.alias().map(alias -> alias.equalsIgnoreCase(qualifier)).orElse(false)) return true;
        }
        return node.children().stream().anyMatch(child -> hasQualifier(child, qualifier));
    }

    private String tableForQualifier(PlanNode node, String qualifier) {
        if (node.scan().isPresent()) {
            ScanSpec scan = node.scan().get();
            if (scan.alias().map(alias -> alias.equalsIgnoreCase(qualifier)).orElse(false)
                    || scan.tableName().equalsIgnoreCase(qualifier)) return scan.tableName();
        }
        for (PlanNode child : node.children()) {
            try { return tableForQualifier(child, qualifier); }
            catch (IllegalArgumentException ignored) { }
        }
        throw new IllegalArgumentException("unknown table or alias '" + qualifier + "'");
    }

    private long distinct(String tableName, String columnName) {
        return stats(tableName).distinctCounts().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(columnName))
                .map(java.util.Map.Entry::getValue).findFirst().orElse(1L);
    }

    private ColumnRef findColumn(PlanNode node, String name) {
        return node.outputColumns().stream().filter(c -> c.name().equalsIgnoreCase(name))
                .flatMap(c -> c.source().stream()).findFirst()
                .orElseThrow(() -> QoErrors.semantic("cannot resolve join column '" + name + "'"));
    }

    private TableStatistics firstStats(PlanNode node) {
        if (node.scan().isPresent()) return stats(node.scan().get().tableName());
        for (PlanNode child : node.children()) {
            try { return firstStats(child); } catch (IllegalStateException ignored) { }
        }
        return new TableStatistics(0, 0, 0, 1, java.util.Map.of());
    }

    TableStatistics statisticsForTable(String table) {
        return withCatalogCache(() -> stats(table));
    }

    private TableStatistics stats(String table) {
        CatalogCache cache = activeCache.get();
        String actualName = actualTableName(table);
        if (cache == null) return storage.getStatistics(actualName);
        return cache.statistics.computeIfAbsent(key(actualName), ignored -> storage.getStatistics(actualName));
    }

    private String actualTableName(String table) {
        CatalogCache cache = activeCache.get();
        if (cache == null)
            return storage.listTables().stream().filter(name -> name.equalsIgnoreCase(table)).findFirst()
                    .orElseThrow(() -> QoErrors.semantic("unknown table '" + table + "'"));
        if (cache.tableNames == null) cache.tableNames = List.copyOf(storage.listTables());
        return cache.tableNames.stream().filter(name -> name.equalsIgnoreCase(table)).findFirst()
                .orElseThrow(() -> QoErrors.semantic("unknown table '" + table + "'"));
    }

    <T> T withCatalogCache(Supplier<T> action) {
        if (activeCache.get() != null) return action.get();
        activeCache.set(new CatalogCache());
        try {
            return action.get();
        } finally {
            activeCache.remove();
        }
    }

    private double estimatedTupleSize(PlanNode node) {
        if (node.kind() == PlanNodeKind.SCAN || node.kind() == PlanNodeKind.INDEX_SCAN) {
            double size = stats(node.scan().orElseThrow().tableName()).averageRecordSize();
            return size > 0 ? size : 0;
        }
        if (node.kind() == PlanNodeKind.JOIN || node.kind() == PlanNodeKind.CARTESIAN) {
            if (node.children().size() != 2) return 0;
            return estimatedTupleSize(node.children().get(0)) + estimatedTupleSize(node.children().get(1));
        }
        if (!node.children().isEmpty()) return estimatedTupleSize(node.children().get(0));
        return 0;
    }

    private static String key(String value) { return value.toLowerCase(Locale.ROOT); }

    private static final class CatalogCache {
        private List<String> tableNames;
        private final Map<String, TableStatistics> statistics = new HashMap<>();
    }
    private static long clampRows(double rows) { return rows <= 0 ? 0 : rows >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(rows); }
    private static long safeMultiply(long a, long b) { return a == 0 || b == 0 ? 0 : a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b; }
    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
    private static boolean isComparison(ExpressionOperator op) {
        return op == ExpressionOperator.EQ || op == ExpressionOperator.NE || op == ExpressionOperator.GT ||
                op == ExpressionOperator.GE || op == ExpressionOperator.LT || op == ExpressionOperator.LE;
    }
    private static ExpressionOperator reverse(ExpressionOperator op) {
        return switch (op) {
            case GT -> ExpressionOperator.LT;
            case GE -> ExpressionOperator.LE;
            case LT -> ExpressionOperator.GT;
            case LE -> ExpressionOperator.GE;
            default -> op;
        };
    }
}
