package sqlike.qo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import sqlike.shared.ColumnRef;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.ExpressionOperator;
import sqlike.shared.JoinType;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.ResultColumn;
import sqlike.shared.SortSpec;

/**
 * Applies selection and projection rewrites to a logical query plan.
 *
 * <p>This class deliberately leaves predicates above a join when their input
 * columns cannot be assigned unambiguously to one side. In particular, a
 * predicate that references both sides of a CARTESIAN node is left there for
 * QO5 to turn into a theta join.</p>
 */
public final class SelectionProjectionRewriter {
    private final CardinalityEstimator cardinalityEstimator;

    /** Creates a rewriter without catalog statistics; safe predicates keep input order. */
    public SelectionProjectionRewriter() {
        this(null);
    }

    /** Creates a rewriter that orders safe conjuncts by estimated selectivity. */
    public SelectionProjectionRewriter(CardinalityEstimator cardinalityEstimator) {
        this.cardinalityEstimator = cardinalityEstimator;
    }

    /** Returns a rewritten plan without mutating the input tree. */
    public PlanNode rewrite(PlanNode root) {
        if (root == null) throw QoErrors.invalidValue("plan root is null", 0);
        PlanNode selectionsRewritten = rewriteSelections(root);
        PlanNode result = ProjectionCleanup.normalize(rewriteProjections(selectionsRewritten));
        PlanNodes.validate(result);
        return result;
    }

    private PlanNode rewriteSelections(PlanNode node) {
        if (isSubqueryBoundary(node)) return node;
        List<PlanNode> children = node.children().stream().map(this::rewriteSelections).toList();
        PlanNode current = withChildren(node, children);
        if (current.kind() != PlanNodeKind.FILTER) return current;

        List<Expression> conjuncts = new ArrayList<>();
        splitConjunction(current.predicate().orElseThrow(), conjuncts);
        if (cardinalityEstimator != null && conjuncts.size() > 1
                && conjuncts.stream().allMatch(this::isSafeToReorder)) {
            PlanNode input = current.children().get(0);
            // New predicates are inserted below existing filters, so build from
            // least to most selective to leave the most selective filter nearest the scan.
            conjuncts.sort(Comparator.comparingDouble(
                    (Expression predicate) -> selectivity(predicate, input)).reversed());
        }
        PlanNode result = current.children().get(0);
        for (Expression predicate : conjuncts) result = pushSelection(predicate, result);
        return result;
    }

    private PlanNode pushSelection(Expression predicate, PlanNode input) {
        if (containsSubquery(predicate)) return filterNode(input, predicate);
        switch (input.kind()) {
            case FILTER -> {
                if (containsSubquery(input.predicate().orElseThrow())) return filterNode(input, predicate);
                // Consecutive selections commute. Keep them as a simple chain;
                // another pass can push the new predicate farther down.
                return filterNode(pushSelection(predicate, input.children().get(0)),
                        input.predicate().orElseThrow());
            }
            case SORT -> {
                return copyUnary(input, pushSelection(predicate, input.children().get(0)));
            }
            case PROJECT -> {
                if (!isAvailableAboveProject(predicate, input.projection()))
                    return filterNode(input, predicate);
                PlanNode below = pushSelection(predicate, input.children().get(0));
                return copyUnary(input, below);
            }
            case JOIN, CARTESIAN -> {
                Side side = predicateSide(predicate, input.children().get(0), input.children().get(1));
                if (side == Side.LEFT) {
                    PlanNode left = pushSelection(predicate, input.children().get(0));
                    return copyBinary(input, left, input.children().get(1));
                }
                if (side == Side.RIGHT) {
                    PlanNode right = pushSelection(predicate, input.children().get(1));
                    return copyBinary(input, input.children().get(0), right);
                }
                return filterNode(input, predicate);
            }
            default -> { return filterNode(input, predicate); }
        }
    }

    private PlanNode rewriteProjections(PlanNode node) {
        if (isSubqueryBoundary(node)) return node;
        List<PlanNode> children = node.children().stream().map(this::rewriteProjections).toList();
        PlanNode current = withChildren(node, children);
        if (current.kind() != PlanNodeKind.PROJECT) return current;

        List<ColumnRef> requested = current.projection();
        PlanNode child = current.children().get(0);
        if (isIdentityProjection(child, requested)) return child;

        // PROJECT(P, PROJECT(Q, x)) is PROJECT(P, x) when P is already
        // available from Q. Retain the chain if the plan is ambiguous.
        if (child.kind() == PlanNodeKind.PROJECT &&
                isSubset(requested, child.projection())) {
            return copyUnary(current, child.children().get(0));
        }

        if (containsWildcard(requested)) return current;
        if (child.kind() == PlanNodeKind.SORT) {
            LinkedHashSet<ColumnRef> required = new LinkedHashSet<>(requested);
            required.add(child.sort().orElseThrow().column());
            PlanNode belowSort = pushProjection(child.children().get(0), List.copyOf(required));
            PlanNode sorted = copyUnary(child, belowSort);
            return copyUnary(current, sorted);
        }
        if (child.kind() == PlanNodeKind.FILTER) {
            if (containsSubquery(child.predicate().orElseThrow())) return current;
            LinkedHashSet<ColumnRef> required = new LinkedHashSet<>(requested);
            required.addAll(columns(child.predicate().orElseThrow()));
            PlanNode belowFilter = pushProjection(child.children().get(0), List.copyOf(required));
            PlanNode filtered = copyUnary(child, belowFilter);
            return copyUnary(current, filtered);
        }
        if (child.kind() == PlanNodeKind.JOIN || child.kind() == PlanNodeKind.CARTESIAN) {
            if (child.join().flatMap(spec -> spec.condition()).filter(this::containsSubquery).isPresent())
                return current;
            PlanNode distributed = distributeProjection(current, child, requested);
            if (distributed != null) return distributed;
        }
        return current;
    }

    private PlanNode pushProjection(PlanNode node, List<ColumnRef> required) {
        if (containsWildcard(required)) return node;
        if (node.kind() == PlanNodeKind.PROJECT) {
            LinkedHashSet<ColumnRef> union = new LinkedHashSet<>(node.projection());
            union.addAll(required);
            return projectNode(pushProjection(node.children().get(0), List.copyOf(union)), List.copyOf(union));
        }
        if (node.kind() == PlanNodeKind.FILTER) {
            if (containsSubquery(node.predicate().orElseThrow())) return projectNode(node, required);
            LinkedHashSet<ColumnRef> union = new LinkedHashSet<>(required);
            union.addAll(columns(node.predicate().orElseThrow()));
            return copyUnary(node, pushProjection(node.children().get(0), List.copyOf(union)));
        }
        if (node.kind() == PlanNodeKind.SORT) {
            LinkedHashSet<ColumnRef> union = new LinkedHashSet<>(required);
            union.add(node.sort().orElseThrow().column());
            return copyUnary(node, pushProjection(node.children().get(0), List.copyOf(union)));
        }
        if (node.kind() == PlanNodeKind.JOIN || node.kind() == PlanNodeKind.CARTESIAN) {
            if (node.join().flatMap(spec -> spec.condition()).filter(this::containsSubquery).isPresent())
                return projectNode(node, required);
            PlanNode distributed = distributeProjection(projectNode(node, required), node, required);
            if (distributed != null) return distributed;
        }
        return projectNode(node, required);
    }

    private PlanNode distributeProjection(PlanNode outerProject, PlanNode join,
                                          List<ColumnRef> requested) {
        List<ColumnRef> joinColumns = new ArrayList<>();
        var joinSpec = join.join().orElseThrow();
        if (joinSpec.condition().filter(this::containsSubquery).isPresent()) return null;
        joinSpec.condition().ifPresent(e -> joinColumns.addAll(columns(e)));
        List<String> commonColumns = joinSpec.commonColumns();
        if (joinSpec.joinType() == JoinType.NATURAL && commonColumns.isEmpty()) return null;

        LinkedHashSet<ColumnRef> leftNeeded = new LinkedHashSet<>();
        LinkedHashSet<ColumnRef> rightNeeded = new LinkedHashSet<>();
        for (ColumnRef col : requested) {
            Side side = columnSide(col, join.children().get(0), join.children().get(1));
            if (side == Side.LEFT) leftNeeded.add(col);
            else if (side == Side.RIGHT) rightNeeded.add(col);
            else return null;
        }
        for (ColumnRef col : joinColumns) {
            Side side = columnSide(col, join.children().get(0), join.children().get(1));
            if (side == Side.LEFT) leftNeeded.add(col);
            else if (side == Side.RIGHT) rightNeeded.add(col);
            else return null;
        }
        for (String name : commonColumns) {
            Optional<ColumnRef> leftRef = commonColumnRef(join.children().get(0), name);
            Optional<ColumnRef> rightRef = commonColumnRef(join.children().get(1), name);
            if (leftRef.isEmpty() || rightRef.isEmpty()) return null;
            leftNeeded.add(leftRef.get());
            rightNeeded.add(rightRef.get());
        }

        if (leftNeeded.isEmpty() && rightNeeded.isEmpty()) return null;
        PlanNode left = leftNeeded.isEmpty()
                ? join.children().get(0) : pushProjection(join.children().get(0), List.copyOf(leftNeeded));
        PlanNode right = rightNeeded.isEmpty()
                ? join.children().get(1) : pushProjection(join.children().get(1), List.copyOf(rightNeeded));
        return copyUnary(outerProject, copyBinary(join, left, right));
    }

    private Optional<ColumnRef> commonColumnRef(PlanNode input, String name) {
        List<ResultColumn> matches = input.outputColumns().stream()
                .filter(column -> column.name().equalsIgnoreCase(name)
                        || column.source().map(source -> source.columnName().equalsIgnoreCase(name)).orElse(false))
                .toList();
        if (matches.size() == 1) return matches.get(0).source();
        return Optional.empty();
    }

    private boolean isAvailableAboveProject(Expression expression, List<ColumnRef> projection) {
        if (containsWildcard(projection)) return true;
        for (ColumnRef ref : columns(expression)) {
            if (!projection.contains(ref)) return false;
        }
        return true;
    }

    private Side predicateSide(Expression expression, PlanNode left, PlanNode right) {
        List<ColumnRef> refs = columns(expression);
        if (refs.isEmpty()) return Side.UNKNOWN;
        Side found = Side.UNKNOWN;
        for (ColumnRef ref : refs) {
            Side side = columnSide(ref, left, right);
            if (side == Side.UNKNOWN) return Side.UNKNOWN;
            if (found != Side.UNKNOWN && found != side) return Side.BOTH;
            found = side;
        }
        return found;
    }

    private Side columnSide(ColumnRef ref, PlanNode left, PlanNode right) {
        if (ref.tableName().isPresent()) {
            boolean inLeft = containsTable(left, ref.tableName().get());
            boolean inRight = containsTable(right, ref.tableName().get());
            if (inLeft == inRight) return Side.UNKNOWN;
            return inLeft ? Side.LEFT : Side.RIGHT;
        }
        Side outputSide = outputColumnSide(ref.columnName(), left, right);
        if (outputSide != Side.UNKNOWN) return outputSide;
        Set<String> leftTables = tables(left), rightTables = tables(right);
        if (leftTables.size() == 1 && rightTables.isEmpty()) return Side.LEFT;
        if (rightTables.size() == 1 && leftTables.isEmpty()) return Side.RIGHT;
        return Side.UNKNOWN;
    }

    private Side outputColumnSide(String name, PlanNode left, PlanNode right) {
        boolean inLeft = hasOutputColumn(name, left);
        boolean inRight = hasOutputColumn(name, right);
        if (inLeft == inRight) return Side.UNKNOWN;
        return inLeft ? Side.LEFT : Side.RIGHT;
    }

    private boolean hasOutputColumn(String name, PlanNode node) {
        return node.outputColumns().stream().anyMatch(c -> c.name().equalsIgnoreCase(name));
    }

    private boolean containsTable(PlanNode node, String table) {
        return tables(node).stream().anyMatch(t -> t.equalsIgnoreCase(table));
    }

    private Set<String> tables(PlanNode node) {
        Set<String> result = new LinkedHashSet<>();
        collectTables(node, result);
        return result;
    }

    private void collectTables(PlanNode node, Set<String> result) {
        node.scan().ifPresent(s -> {
            result.add(s.tableName());
            s.alias().ifPresent(result::add);
        });
        node.children().forEach(child -> collectTables(child, result));
    }

    private void splitConjunction(Expression expression, List<Expression> result) {
        if (expression.kind() == ExpressionKind.BINARY &&
                expression.operator().orElse(null) == ExpressionOperator.AND &&
                expression.operands().size() == 2) {
            splitConjunction(expression.operands().get(0), result);
            splitConjunction(expression.operands().get(1), result);
        } else result.add(expression);
    }

    List<ColumnRef> columns(Expression expression) {
        LinkedHashSet<ColumnRef> result = new LinkedHashSet<>();
        collectColumns(expression, result);
        return List.copyOf(result);
    }

    private void collectColumns(Expression expression, Set<ColumnRef> result) {
        if (expression.kind() == ExpressionKind.COLUMN)
            expression.column().ifPresent(result::add);
        // A correlated subquery depends on the outer columns it reads, so they count as columns of the predicate.
        expression.subquery().ifPresent(subquery -> result.addAll(outerReferences(subquery.root())));
        expression.operands().forEach(child -> collectColumns(child, result));
    }

    /** Columns read inside {@code root} (nested subqueries included) whose qualifier is not a scan of {@code root} itself. */
    private Set<ColumnRef> outerReferences(PlanNode root) {
        Set<String> inner = new LinkedHashSet<>();
        for (String name : tables(root)) inner.add(name.toLowerCase(java.util.Locale.ROOT));
        Set<ColumnRef> read = new LinkedHashSet<>();
        collectReads(root, read);
        Set<ColumnRef> outer = new LinkedHashSet<>();
        for (ColumnRef ref : read)
            if (ref.tableName().isPresent() && !inner.contains(ref.tableName().get().toLowerCase(java.util.Locale.ROOT)))
                outer.add(ref);
        return outer;
    }

    private void collectReads(PlanNode node, Set<ColumnRef> read) {
        read.addAll(node.projection());
        node.sort().ifPresent(sort -> read.add(sort.column()));
        node.predicate().ifPresent(predicate -> collectColumns(predicate, read));
        node.join().flatMap(spec -> spec.condition()).ifPresent(condition -> collectColumns(condition, read));
        node.mutation().flatMap(mutation -> mutation.updateExpression()).ifPresent(value -> collectColumns(value, read));
        node.children().forEach(child -> collectReads(child, read));
    }

    private boolean containsSubquery(Expression expression) {
        return expression.kind() == ExpressionKind.SUBQUERY || expression.subquery().isPresent()
                || expression.operands().stream().anyMatch(this::containsSubquery);
    }

    private boolean isSubqueryBoundary(PlanNode node) {
        return node.predicate().filter(this::containsSubquery).isPresent()
                || node.join().flatMap(spec -> spec.condition()).filter(this::containsSubquery).isPresent();
    }

    private boolean isSafeToReorder(Expression expression) {
        if (containsSubquery(expression) || expression.kind() != ExpressionKind.BINARY
                || expression.operands().size() != 2) return false;
        ExpressionOperator operator = expression.operator().orElse(null);
        if (operator != ExpressionOperator.EQ && operator != ExpressionOperator.NE
                && operator != ExpressionOperator.GT && operator != ExpressionOperator.GE
                && operator != ExpressionOperator.LT && operator != ExpressionOperator.LE) return false;
        Expression left = expression.operands().get(0), right = expression.operands().get(1);
        return (left.kind() == ExpressionKind.COLUMN && right.kind() == ExpressionKind.LITERAL)
                || (left.kind() == ExpressionKind.LITERAL && right.kind() == ExpressionKind.COLUMN)
                || (left.kind() == ExpressionKind.COLUMN && right.kind() == ExpressionKind.COLUMN);
    }

    private double selectivity(Expression predicate, PlanNode input) {
        try {
            return cardinalityEstimator.selectivity(predicate, input);
        } catch (RuntimeException ignored) {
            return 0.5;
        }
    }

    private boolean isIdentityProjection(PlanNode input, List<ColumnRef> requested) {
        if (requested.isEmpty() || requested.size() != input.outputColumns().size()) return false;
        List<ResultColumn> mapped = projectOutputColumns(input, requested, List.of());
        return mapped.size() == input.outputColumns().size() && mapped.equals(input.outputColumns());
    }

    private boolean isSubset(List<ColumnRef> requested, List<ColumnRef> available) {
        if (containsWildcard(available)) return true;
        return available.containsAll(requested);
    }

    private boolean containsWildcard(List<ColumnRef> columns) {
        return columns.stream().anyMatch(c -> c.columnName().equals("*"));
    }

    private PlanNode withChildren(PlanNode n, List<PlanNode> children) {
        List<ResultColumn> outputs = switch (n.kind()) {
            case FILTER, SORT, LIMIT -> children.isEmpty() ? n.outputColumns() : children.get(0).outputColumns();
            case PROJECT -> children.isEmpty() ? n.outputColumns()
                    : projectOutputColumns(children.get(0), n.projection(), n.outputColumns());
            case JOIN, CARTESIAN -> children.size() == 2
                    ? joinOutputColumns(n, children.get(0), children.get(1)) : n.outputColumns();
            default -> n.outputColumns();
        };
        return new PlanNode(n.kind(), children, outputs, n.estimatedRows(), n.scan(), n.predicate(),
                n.projection(), n.join(), n.sort(), n.mutation(), n.createIndex(),
                n.limit(), n.createTable(), n.dropTable());
    }

    private PlanNode filterNode(PlanNode child, Expression predicate) {
        PlanNode filter = PlanNodes.filter(child, predicate);
        return withChildren(filter, filter.children());
    }

    private PlanNode projectNode(PlanNode child, List<ColumnRef> columns) {
        if (isIdentityProjection(child, columns)) return child;
        PlanNode project = PlanNodes.project(child, columns);
        return withChildren(project, project.children());
    }

    private List<ResultColumn> projectOutputColumns(PlanNode child, List<ColumnRef> columns,
                                                     List<ResultColumn> fallback) {
        if (child.outputColumns().isEmpty()) return fallback;
        List<ResultColumn> outputs = new ArrayList<>();
        for (ColumnRef requested : columns) {
            ResultColumn match = findOutputColumn(child, child.outputColumns(), requested);
            if (match == null) return fallback;
            outputs.add(match);
        }
        return List.copyOf(outputs);
    }

    private ResultColumn findOutputColumn(PlanNode input, List<ResultColumn> outputs, ColumnRef requested) {
        List<ResultColumn> matches = outputs.stream().filter(output -> {
            if (requested.tableName().isPresent()) {
                return output.source().filter(source -> source.tableName().isPresent()
                        && qualifierMatches(input, requested.tableName().get(), source.tableName().get())
                        && source.columnName().equalsIgnoreCase(requested.columnName())).isPresent();
            }
            return output.name().equalsIgnoreCase(requested.columnName())
                    || output.source().map(source -> source.columnName().equalsIgnoreCase(requested.columnName())).orElse(false);
        }).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private boolean qualifierMatches(PlanNode input, String requested, String sourceTable) {
        if (requested.equalsIgnoreCase(sourceTable)) return true;
        return scans(input).stream().anyMatch(scan -> scan.tableName().equalsIgnoreCase(sourceTable)
                && scan.alias().map(alias -> alias.equalsIgnoreCase(requested)).orElse(false));
    }

    private List<sqlike.shared.ScanSpec> scans(PlanNode node) {
        List<sqlike.shared.ScanSpec> result = new ArrayList<>();
        node.scan().ifPresent(result::add);
        node.children().forEach(child -> result.addAll(scans(child)));
        return result;
    }

    private List<ResultColumn> joinOutputColumns(PlanNode join, PlanNode left, PlanNode right) {
        List<ResultColumn> outputs = new ArrayList<>(left.outputColumns());
        List<String> common = join.join().filter(spec -> spec.joinType() == JoinType.NATURAL)
                .map(spec -> spec.commonColumns()).orElse(List.of());
        for (ResultColumn column : right.outputColumns()) {
            boolean duplicateNaturalColumn = common.stream().anyMatch(name -> name.equalsIgnoreCase(column.name()));
            if (!duplicateNaturalColumn) outputs.add(column);
        }
        return List.copyOf(outputs);
    }

    private PlanNode copyUnary(PlanNode n, PlanNode child) {
        return withChildren(n, List.of(child));
    }

    private PlanNode copyBinary(PlanNode n, PlanNode left, PlanNode right) {
        return withChildren(n, List.of(left, right));
    }

    private enum Side { LEFT, RIGHT, BOTH, UNKNOWN }
}


