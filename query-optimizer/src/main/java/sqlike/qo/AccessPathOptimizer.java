package sqlike.qo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sqlike.contract.IStorageManager;
import sqlike.shared.*;

/** QO-13: bounded scan/index alternatives, compared as complete plans by QO3. No record I/O. */
public class AccessPathOptimizer {
    private final IStorageManager storage;
    private final int maxCandidates;
    private final PlanSelector selector = new PlanSelector();
    private final CardinalityEstimator estimator;

    public AccessPathOptimizer(IStorageManager storage) { this(storage, 64); }

    public AccessPathOptimizer(IStorageManager storage, int maxCandidates) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        if (maxCandidates < 2) throw new IllegalArgumentException("at least two candidates are required");
        this.storage = storage;
        this.maxCandidates = maxCandidates;
        this.estimator = new CardinalityEstimator(storage);
    }

    /** The scan baseline wins cost ties; residual predicates and ordered output metadata survive. */
    public PlanNode chooseAccessPath(PlanNode plan, CostModel cost) {
        if (cost == null) throw QoErrors.invalidState("chooseAccessPath requires a cost model");
        // Compare the shapes actually returned to QP, not wrappers that final cleanup would remove.
        List<PlanNode> candidates = enumerateAccessPaths(plan).stream()
                .map(QO5Plans::simplifyIdentityProjects).toList();
        return selector.pickCheapest(candidates, cost);
    }

    /** Package-private for the QO5 pipeline/tests; catalog snapshots are local to this invocation. */
    List<PlanNode> enumerateAccessPaths(PlanNode plan) {
        QO5Plans.requirePlan(plan);
        return alternatives(plan, new Catalog());
    }

    private List<PlanNode> alternatives(PlanNode plan, Catalog catalog) {
        if (plan.kind() == PlanNodeKind.LIMIT || QO5Plans.hasSubquery(plan)) return List.of(plan);
        PlanNode scan = scanBelow(plan);
        if (scan != null) return scanAlternatives(plan, scan, catalog);

        // Combine child alternatives within the budget, always retaining the unchanged plan first.
        List<PlanNode> states = List.of(plan);
        for (int i = 0; i < plan.children().size(); i++) {
            List<PlanNode> children = alternatives(plan.children().get(i), catalog);
            Set<PlanNode> next = new LinkedHashSet<>();
            for (PlanNode state : states) {
                for (PlanNode child : children) {
                    List<PlanNode> inputs = new ArrayList<>(state.children());
                    inputs.set(i, child);
                    next.add(QO5Plans.withChildren(state, inputs));
                    if (next.size() >= maxCandidates) break;
                }
                if (next.size() >= maxCandidates) break;
            }
            states = List.copyOf(next);
        }
        return states;
    }

    private PlanNode scanBelow(PlanNode node) {
        if (node.kind() == PlanNodeKind.SCAN) return node;
        if (node.kind() == PlanNodeKind.FILTER || node.kind() == PlanNodeKind.PROJECT)
            return scanBelow(node.children().get(0));
        return null;
    }

    private List<PlanNode> scanAlternatives(PlanNode original, PlanNode scan, Catalog catalog) {
        List<Term> terms = new ArrayList<>();
        for (Expression predicate : predicates(original)) {
            Term term = term(predicate, scan);
            if (term != null) terms.add(term);
        }
        if (terms.isEmpty()) return List.of(original);
        Set<PlanNode> candidates = new LinkedHashSet<>();
        candidates.add(original);
        ScanSpec spec = scan.scan().orElseThrow();
        for (IndexMetadata index : catalog.indexes(spec.tableName())) {
            if (!index.tableName().equalsIgnoreCase(spec.tableName())) continue;
            List<Term> matching = terms.stream()
                    .filter(term -> term.column().columnName().equalsIgnoreCase(index.columnName())).toList();
            // Evaluate equality and paired ranges before single bounds so BETWEEN is not starved.
            for (Term term : matching) {
                if (term.operator() == IndexOperator.EQ
                        && add(candidates, original, index, new Lookup(single(term), Set.of(term.predicate()))))
                    return List.copyOf(candidates);
            }
            if (index.indexType() != IndexType.BPLUS_TREE) continue;
            for (Term lower : matching) for (Term upper : matching) {
                if (!isLower(lower.operator()) || !isUpper(upper.operator())) continue;
                if (compare(lower.key(), upper.key()) > 0) continue;
                IndexCondition condition = new IndexCondition(IndexOperator.BETWEEN, lower.key(), Optional.of(upper.key()),
                        lower.operator() == IndexOperator.GE, upper.operator() == IndexOperator.LE);
                if (add(candidates, original, index, new Lookup(condition, Set.of(lower.predicate(), upper.predicate()))))
                    return List.copyOf(candidates);
            }
            for (Term term : matching) {
                if (term.operator() != IndexOperator.EQ
                        && add(candidates, original, index, new Lookup(single(term), Set.of(term.predicate()))))
                    return List.copyOf(candidates);
            }
        }
        return List.copyOf(candidates);
    }

    private boolean add(Set<PlanNode> candidates, PlanNode original, IndexMetadata index, Lookup lookup) {
        PlanNode candidate = replace(original, index, lookup);
        PlanNodes.validate(candidate);
        // QO3 owns selectivity. Until its two-bound filter and BETWEEN models agree, retain the
        // baseline/single-bound alternatives rather than rank equivalent forms on different row counts.
        // No duplicate formula or artificial residual predicate is added to compensate for the gap.
        if (lookup.condition().operator() == IndexOperator.BETWEEN
                && !estimator.withCatalogCache(() -> estimator.estimateRows(original) == estimator.estimateRows(candidate)))
            return false;
        candidates.add(candidate);
        return candidates.size() >= maxCandidates;
    }

    private PlanNode replace(PlanNode node, IndexMetadata index, Lookup lookup) {
        if (node.kind() == PlanNodeKind.SCAN) {
            ScanSpec original = node.scan().orElseThrow();
            ScanSpec spec = new ScanSpec(original.tableName(), Optional.of(index.indexName()),
                    Optional.of(lookup.condition()), original.alias());
            return new PlanNode(PlanNodeKind.INDEX_SCAN, List.of(), node.outputColumns(), Optional.empty(),
                    Optional.of(spec), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), node.limit(), node.createTable(), node.dropTable());
        }
        PlanNode child = replace(node.children().get(0), index, lookup);
        if (node.kind() == PlanNodeKind.PROJECT) return QO5Plans.withChildren(node, List.of(child));
        List<Expression> residual = QO5Plans.conjuncts(node.predicate().orElseThrow()).stream()
                .filter(predicate -> !lookup.consumed().contains(predicate)).toList();
        return QO5Plans.conjunction(residual).map(predicate -> QO5Plans.filter(child, predicate)).orElse(child);
    }

    private List<Expression> predicates(PlanNode node) {
        if (node.kind() == PlanNodeKind.SCAN) return List.of();
        List<Expression> result = new ArrayList<>(predicates(node.children().get(0)));
        node.predicate().ifPresent(predicate -> result.addAll(QO5Plans.conjuncts(predicate)));
        return result;
    }

    private Term term(Expression predicate, PlanNode scan) {
        if (predicate.kind() != ExpressionKind.BINARY || predicate.operands().size() != 2) return null;
        Expression left = predicate.operands().get(0), right = predicate.operands().get(1);
        ExpressionOperator operator = predicate.operator().orElse(null);
        if (left.kind() == ExpressionKind.LITERAL && right.kind() == ExpressionKind.COLUMN) {
            Expression temporary = left; left = right; right = temporary;
            operator = reverse(operator);
        }
        if (left.kind() != ExpressionKind.COLUMN || right.kind() != ExpressionKind.LITERAL) return null;
        IndexOperator indexOperator = indexOperator(operator);
        if (indexOperator == null) return null; // NE and arithmetic are not a single index lookup.
        ColumnRef column = left.column().orElseThrow();
        if (column.tableName().isEmpty()) return null;
        List<ResultColumn> sources = scan.outputColumns().stream()
                .filter(output -> output.source().map(source -> QO5Plans.key(source).equals(QO5Plans.key(column))).orElse(false))
                .toList();
        if (sources.size() != 1) return null;
        Value key = typedKey(right.literal().orElseThrow(), sources.get(0).dataType());
        return key == null ? null : new Term(predicate, column, indexOperator, key);
    }

    /** Only exact conversions: fractional/out-of-range FLOAT keys for INTEGER columns retain scans. */
    private Value typedKey(Value value, DataType target) {
        if (value.type() == DataType.FLOAT && !Double.isFinite(value.floatValue().orElseThrow())) return null;
        if (value.type() == target) return value;
        if (target == DataType.FLOAT && value.type() == DataType.INTEGER)
            return new Value(target, Optional.empty(), Optional.of(value.integerValue().orElseThrow().doubleValue()), Optional.empty());
        if (target == DataType.INTEGER && value.type() == DataType.FLOAT) {
            double number = value.floatValue().orElseThrow();
            if (!Double.isFinite(number) || number != Math.rint(number)
                    || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
            if (Double.doubleToRawLongBits(number) == Long.MIN_VALUE) return null; // retain signed-zero semantics
            return new Value(target, Optional.of((int) number), Optional.empty(), Optional.empty());
        }
        if ((target == DataType.CHAR || target == DataType.VARCHAR)
                && (value.type() == DataType.CHAR || value.type() == DataType.VARCHAR))
            return new Value(target, Optional.empty(), Optional.empty(), value.textValue());
        return null;
    }

    private IndexCondition single(Term term) {
        return new IndexCondition(term.operator(), term.key(), Optional.empty(),
                term.operator() == IndexOperator.EQ || term.operator() == IndexOperator.GE,
                term.operator() == IndexOperator.EQ || term.operator() == IndexOperator.LE);
    }

    private boolean isLower(IndexOperator operator) { return operator == IndexOperator.GT || operator == IndexOperator.GE; }
    private boolean isUpper(IndexOperator operator) { return operator == IndexOperator.LT || operator == IndexOperator.LE; }

    private ExpressionOperator reverse(ExpressionOperator operator) {
        if (operator == null) return null;
        return switch (operator) {
            case GT -> ExpressionOperator.LT;
            case GE -> ExpressionOperator.LE;
            case LT -> ExpressionOperator.GT;
            case LE -> ExpressionOperator.GE;
            default -> operator;
        };
    }

    private IndexOperator indexOperator(ExpressionOperator operator) {
        if (operator == null) return null;
        return switch (operator) {
            case EQ -> IndexOperator.EQ;
            case GT -> IndexOperator.GT;
            case GE -> IndexOperator.GE;
            case LT -> IndexOperator.LT;
            case LE -> IndexOperator.LE;
            default -> null;
        };
    }

    private int compare(Value left, Value right) {
        return switch (left.type()) {
            case INTEGER -> Integer.compare(left.integerValue().orElseThrow(), right.integerValue().orElseThrow());
            case FLOAT -> Double.compare(left.floatValue().orElseThrow(), right.floatValue().orElseThrow());
            case CHAR, VARCHAR -> java.util.Arrays.compare(left.textValue().orElseThrow().codePoints().toArray(),
                    right.textValue().orElseThrow().codePoints().toArray());
        };
    }

    private record Term(Expression predicate, ColumnRef column, IndexOperator operator, Value key) { }
    private record Lookup(IndexCondition condition, Set<Expression> consumed) { }

    private final class Catalog {
        private final Map<String, List<IndexMetadata>> indexes = new HashMap<>();

        List<IndexMetadata> indexes(String table) {
            return indexes.computeIfAbsent(table.toLowerCase(Locale.ROOT), ignored -> {
                String canonical = storage.listTables().stream().filter(name -> name.equalsIgnoreCase(table)).findFirst()
                        .orElseThrow(() -> QoErrors.semantic("unknown table '" + table + "'"));
                List<IndexMetadata> result = storage.getIndexes(canonical);
                if (result == null) throw QoErrors.invalidState("index catalog must not return null");
                return result.stream().sorted(Comparator.comparing(IndexMetadata::indexName, String.CASE_INSENSITIVE_ORDER)).toList();
            });
        }
    }
}
