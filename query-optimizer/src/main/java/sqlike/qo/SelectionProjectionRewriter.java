package sqlike.qo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import sqlike.shared.ColumnRef;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.ExpressionOperator;
import sqlike.shared.JoinType;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
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

    /** Returns a rewritten plan without mutating the input tree. */
    public PlanNode rewrite(PlanNode root) {
        if (root == null) throw QoErrors.invalidValue("plan root is null", 0);
        PlanNode selectionsRewritten = rewriteSelections(root);
        PlanNode result = rewriteProjections(selectionsRewritten);
        PlanNodes.validate(result);
        return result;
    }

    private PlanNode rewriteSelections(PlanNode node) {
        List<PlanNode> children = node.children().stream().map(this::rewriteSelections).toList();
        PlanNode current = withChildren(node, children);
        if (current.kind() != PlanNodeKind.FILTER) return current;

        List<Expression> conjuncts = new ArrayList<>();
        splitConjunction(current.predicate().orElseThrow(), conjuncts);
        PlanNode result = current.children().get(0);
        for (Expression predicate : conjuncts) result = pushSelection(predicate, result);
        return result;
    }

    private PlanNode pushSelection(Expression predicate, PlanNode input) {
        switch (input.kind()) {
            case FILTER -> {
                // Consecutive selections commute. Keep them as a simple chain;
                // another pass can push the new predicate farther down.
                return PlanNodes.filter(pushSelection(predicate, input.children().get(0)),
                        input.predicate().orElseThrow());
            }
            case SORT -> {
                return copyUnary(input, pushSelection(predicate, input.children().get(0)));
            }
            case PROJECT -> {
                if (!isAvailableAboveProject(predicate, input.projection()))
                    return PlanNodes.filter(input, predicate);
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
                return PlanNodes.filter(input, predicate);
            }
            default -> { return PlanNodes.filter(input, predicate); }
        }
    }

    private PlanNode rewriteProjections(PlanNode node) {
        List<PlanNode> children = node.children().stream().map(this::rewriteProjections).toList();
        PlanNode current = withChildren(node, children);
        if (current.kind() != PlanNodeKind.PROJECT) return current;

        List<ColumnRef> requested = current.projection();
        PlanNode child = current.children().get(0);

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
            LinkedHashSet<ColumnRef> required = new LinkedHashSet<>(requested);
            required.addAll(columns(child.predicate().orElseThrow()));
            PlanNode belowFilter = pushProjection(child.children().get(0), List.copyOf(required));
            PlanNode filtered = copyUnary(child, belowFilter);
            return copyUnary(current, filtered);
        }
        if (child.kind() == PlanNodeKind.JOIN || child.kind() == PlanNodeKind.CARTESIAN) {
            PlanNode distributed = distributeProjection(current, child, requested);
            if (distributed != null) return distributed;
        }
        return current;
    }

    private PlanNode pushProjection(PlanNode node, List<ColumnRef> required) {
        if (containsWildcard(required)) return node;
        if (node.kind() == PlanNodeKind.PROJECT) return node;
        if (node.kind() == PlanNodeKind.FILTER) {
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
            PlanNode distributed = distributeProjection(PlanNodes.project(node, required), node, required);
            if (distributed != null) return distributed;
        }
        return PlanNodes.project(node, required);
    }

    private PlanNode distributeProjection(PlanNode outerProject, PlanNode join,
                                          List<ColumnRef> requested) {
        List<ColumnRef> joinColumns = new ArrayList<>();
        var joinSpec = join.join().orElseThrow();
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
            ColumnRef common = ColumnRef.of(null, name);
            leftNeeded.add(common);
            rightNeeded.add(common);
        }

        if (leftNeeded.isEmpty() || rightNeeded.isEmpty()) return null;
        PlanNode left = pushProjection(join.children().get(0), List.copyOf(leftNeeded));
        PlanNode right = pushProjection(join.children().get(1), List.copyOf(rightNeeded));
        return copyUnary(outerProject, copyBinary(join, left, right));
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
        node.scan().ifPresent(s -> result.add(s.tableName()));
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

    private List<ColumnRef> columns(Expression expression) {
        LinkedHashSet<ColumnRef> result = new LinkedHashSet<>();
        collectColumns(expression, result);
        return List.copyOf(result);
    }

    private void collectColumns(Expression expression, Set<ColumnRef> result) {
        if (expression.kind() == ExpressionKind.COLUMN)
            expression.column().ifPresent(result::add);
        expression.operands().forEach(child -> collectColumns(child, result));
    }

    private boolean isSubset(List<ColumnRef> requested, List<ColumnRef> available) {
        if (containsWildcard(available)) return true;
        return available.containsAll(requested);
    }

    private boolean containsWildcard(List<ColumnRef> columns) {
        return columns.stream().anyMatch(c -> c.columnName().equals("*"));
    }

    private PlanNode withChildren(PlanNode n, List<PlanNode> children) {
        return new PlanNode(n.kind(), children, n.outputColumns(), n.estimatedRows(), n.scan(), n.predicate(),
                n.projection(), n.join(), n.sort(), n.mutation(), n.createIndex());
    }

    private PlanNode copyUnary(PlanNode n, PlanNode child) {
        return withChildren(n, List.of(child));
    }

    private PlanNode copyBinary(PlanNode n, PlanNode left, PlanNode right) {
        return withChildren(n, List.of(left, right));
    }

    private enum Side { LEFT, RIGHT, BOTH, UNKNOWN }
}

