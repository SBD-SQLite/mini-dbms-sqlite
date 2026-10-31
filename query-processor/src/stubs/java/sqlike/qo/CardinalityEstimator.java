package sqlike.qo;

import sqlike.contract.IStorageManager;
import sqlike.shared.*;

public final class CardinalityEstimator {
    private final IStorageManager storage;

    public CardinalityEstimator(IStorageManager storage) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        this.storage = storage;
    }

    public long estimateRows(PlanNode node) {
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
        };
    }

    public long estimateBlocks(PlanNode node) {
        if (node.kind() == PlanNodeKind.SCAN)
            return stats(node.scan().orElseThrow().tableName()).blockCount();
        if (node.kind() == PlanNodeKind.INDEX_SCAN) {
            String table = node.scan().orElseThrow().tableName();
            long tableBlocks = stats(table).blockCount();
            long qualifyingRows = estimateRows(node);
            if (tableBlocks <= 0 || qualifyingRows <= 0) return 0;

            // Assume qualifying RIDs are uniformly scattered across table pages.
            // Expected distinct pages = b * (1 - (1 - 1/b)^n). This is an
            // unclustered-index heuristic until SM exposes clustering metadata.
            double expectedPages = -((double) tableBlocks) *
                    Math.expm1(qualifyingRows * Math.log1p(-1.0 / tableBlocks));
            long roundedPages = (long) Math.ceil(expectedPages);
            return Math.min(tableBlocks, Math.max(1, roundedPages));
        }
        long rows = estimateRows(node);
        if (node.children().isEmpty()) return rows == 0 ? 0 : 1;
        TableStatistics source = firstStats(node);
        int factor = Math.max(1, source.blockingFactor());
        return rows == 0 ? 0 : 1 + (rows - 1) / factor;
    }

    public double selectivity(Expression condition, PlanNode child) {
        if (condition.kind() == ExpressionKind.BINARY && condition.operator().orElse(null) == ExpressionOperator.AND && condition.operands().size() == 2)
            return clamp(selectivity(condition.operands().get(0), child) * selectivity(condition.operands().get(1), child));
        if (condition.kind() == ExpressionKind.BINARY) {
            ExpressionOperator op = condition.operator().orElse(null);
            if (condition.operands().size() == 2 && isComparison(op)) {
                Expression left = condition.operands().get(0), right = condition.operands().get(1);
                if (left.kind() == ExpressionKind.COLUMN && right.kind() == ExpressionKind.LITERAL)
                    return comparisonSelectivity(left.column().orElseThrow(), op);
                if (right.kind() == ExpressionKind.COLUMN && left.kind() == ExpressionKind.LITERAL)
                    return comparisonSelectivity(right.column().orElseThrow(), reverse(op));
                if (left.kind() == ExpressionKind.COLUMN && right.kind() == ExpressionKind.COLUMN)
                    return op == ExpressionOperator.EQ
                            ? 1.0 / Math.max(distinct(left.column().orElseThrow()), distinct(right.column().orElseThrow()))
                            : (op == ExpressionOperator.NE ? 0.9 : 1.0 / 3.0);
            }
        }
        return 0.5;
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
                selectivity /= Math.max(distinct(l), distinct(r));
            }
            return clampRows(leftRows * (double) rightRows * selectivity);
        }
        if (join.condition().isPresent()) return clampRows(leftRows * (double) rightRows * selectivity(join.condition().get(), node));
        return safeMultiply(leftRows, rightRows);
    }

    private double comparisonSelectivity(ColumnRef column, ExpressionOperator op) {
        long distinct = Math.max(1, distinct(column));
        return switch (op) {
            case EQ -> 1.0 / distinct;
            case NE -> 1.0 - 1.0 / distinct;
            // No min/max histogram is part of the SM statistics contract yet.
            // Use a uniform-distribution heuristic, to be refined with SM4.
            case GT, GE, LT, LE -> 1.0 / 3.0;
            default -> 0.5;
        };
    }

    private double indexSelectivity(ScanSpec scan) {
        IndexCondition condition = scan.condition().orElseThrow();
        String table = scan.tableName();
        String indexName = scan.indexName().orElseThrow();
        String columnName;
        try {
            columnName = storage.getIndexes(actualTableName(table)).stream()
                    .filter(index -> index.indexName().equalsIgnoreCase(indexName))
                    .map(IndexMetadata::columnName).findFirst().orElse("");
        } catch (sqlike.storage.StorageException e) {
            columnName = "";
        }
        return switch (condition.operator()) {
            case EQ -> 1.0 / Math.max(1, distinct(table, columnName));
            case GT, GE, LT, LE, BETWEEN -> 1.0 / 3.0;
        };
    }

    private long estimateRowsForScan(String table) { return stats(table).recordCount(); }

    private long distinct(ColumnRef column) {
        String tableName = column.tableName().orElseThrow(() -> QoErrors.semantic("unqualified column in cardinality estimate"));
        return Math.max(1, distinct(tableName, column.columnName()));
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

    private TableStatistics stats(String table) {
        try {
            return storage.getStatistics(actualTableName(table));
        } catch (sqlike.storage.StorageException e) {
            return new TableStatistics(0, 0, 0, 1, java.util.Map.of());
        }
    }

    private String actualTableName(String table) {
        try {
            return storage.listTables().stream().filter(name -> name.equalsIgnoreCase(table)).findFirst()
                    .orElseThrow(() -> QoErrors.semantic("unknown table '" + table + "'"));
        } catch (sqlike.storage.StorageException e) {
            throw QoErrors.semantic("unknown table '" + table + "'");
        }
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
