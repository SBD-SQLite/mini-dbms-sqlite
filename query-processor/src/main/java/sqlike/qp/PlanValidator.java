package sqlike.qp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import sqlike.shared.Expression;
import sqlike.shared.ExpressionKind;
import sqlike.shared.JoinSpec;
import sqlike.shared.JoinType;
import sqlike.shared.MutationSpec;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.QueryPlan;
import sqlike.shared.SubqueryType;

/**
 * PlanValidator validates query plan trees for structural integrity, cycle freedom, child arity,
 * node parameters, expression structure, and column references matching CLASS DIAGRAM FIX and PRD §7.
 */
public class PlanValidator {

    public PlanValidator() {
    }

    public void validate(PreparedStatement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("PreparedStatement cannot be null");
        }
        validate(statement.plan());
    }

    public void validate(QueryPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("QueryPlan cannot be null");
        }
        validate(plan.root());
    }

    public void validate(PlanNode root) {
        if (root == null) {
            throw new IllegalArgumentException("PlanNode root cannot be null");
        }
        Set<PlanNode> path = Collections.newSetFromMap(new IdentityHashMap<>());
        validateNode(root, path);
    }

    private void validateNode(PlanNode node, Set<PlanNode> path) {
        if (node == null) {
            throw new IllegalArgumentException("PlanNode child cannot be null");
        }

        if (!path.add(node)) {
            throw new IllegalArgumentException("Cyclic reference detected in query plan tree at node kind: " + node.kind());
        }

        int expected = expectedArity(node.kind());
        if (node.children().size() != expected) {
            throw new IllegalArgumentException(
                    "Invalid child count for " + node.kind() + ": expected " + expected + ", but found " + node.children().size());
        }

        validatePayloadKinds(node);
        validateParameters(node, path);
        validateColumnReferences(node);

        for (PlanNode child : node.children()) {
            validateNode(child, path);
        }

        path.remove(node);
    }

    private void validatePayloadKinds(PlanNode node) {
        PlanNodeKind kind = node.kind();
        boolean scan = kind == PlanNodeKind.SCAN || kind == PlanNodeKind.INDEX_SCAN || kind == PlanNodeKind.DELETE;
        boolean join = kind == PlanNodeKind.JOIN || kind == PlanNodeKind.CARTESIAN;
        boolean mutation = kind == PlanNodeKind.INSERT || kind == PlanNodeKind.UPDATE || kind == PlanNodeKind.DELETE;
        if ((!scan && node.scan().isPresent()) || (!join && node.join().isPresent())
                || (!mutation && node.mutation().isPresent())
                || (kind != PlanNodeKind.FILTER && node.predicate().isPresent())
                || (kind != PlanNodeKind.PROJECT && !node.projection().isEmpty())
                || (kind != PlanNodeKind.SORT && node.sort().isPresent())
                || (kind != PlanNodeKind.LIMIT && node.limit().isPresent())
                || (kind != PlanNodeKind.CREATE_INDEX && node.createIndex().isPresent())
                || (kind != PlanNodeKind.CREATE_TABLE && node.createTable().isPresent())
                || (kind != PlanNodeKind.DROP_TABLE && node.dropTable().isPresent())) {
            throw new IllegalArgumentException("Payload does not match plan node kind: " + kind);
        }
    }

    private void validateParameters(PlanNode node, Set<PlanNode> path) {
        switch (node.kind()) {
            case SCAN -> {
                if (node.scan().isEmpty()
                        || node.scan().get().tableName() == null
                        || node.scan().get().tableName().isBlank()) {
                    throw new IllegalArgumentException("SCAN node must contain ScanSpec with non-blank tableName");
                }
                if (node.scan().get().indexName().isPresent() || node.scan().get().condition().isPresent()) {
                    throw new IllegalArgumentException("SCAN must not contain index parameters");
                }
            }
            case INDEX_SCAN -> {
                if (node.scan().isEmpty()
                        || node.scan().get().tableName() == null
                        || node.scan().get().tableName().isBlank()
                        || node.scan().get().indexName().isEmpty()
                        || node.scan().get().indexName().get().isBlank()) {
                    throw new IllegalArgumentException("INDEX_SCAN node must contain ScanSpec with tableName and indexName");
                }
                var condition = node.scan().get().condition().orElseThrow(
                        () -> new IllegalArgumentException("INDEX_SCAN requires an index condition"));
                if (condition.operator() == null || condition.key() == null
                        || (condition.operator() == sqlike.shared.IndexOperator.BETWEEN && condition.upperBound().isEmpty())) {
                    throw new IllegalArgumentException("Invalid index condition");
                }
            }
            case FILTER -> {
                if (node.predicate().isEmpty()) {
                    throw new IllegalArgumentException("FILTER node must contain predicate Expression");
                }
            }
            case PROJECT -> {
                if (node.projection().isEmpty()) {
                    throw new IllegalArgumentException("PROJECT node must contain non-empty projection ColumnRef list");
                }
            }
            case JOIN -> {
                if (node.join().isEmpty()) {
                    throw new IllegalArgumentException("JOIN node must contain JoinSpec parameters");
                }
                JoinSpec joinSpec = node.join().get();
                if (joinSpec.joinType() == null) throw new IllegalArgumentException("JOIN requires a join type");
                if (joinSpec.joinType() == JoinType.THETA && joinSpec.condition().isEmpty()) {
                    throw new IllegalArgumentException("THETA JOIN node must contain a join condition");
                }
                if (joinSpec.joinType() == JoinType.CARTESIAN && joinSpec.condition().isPresent()) {
                    throw new IllegalArgumentException("CARTESIAN JOIN node must not contain a join condition");
                }
            }
            case CARTESIAN -> {
                if (node.join().isPresent() && node.join().get().joinType() != JoinType.CARTESIAN) {
                    throw new IllegalArgumentException("CARTESIAN has a conflicting join type");
                }
                if (node.join().isPresent() && node.join().get().condition().isPresent()) {
                    throw new IllegalArgumentException("CARTESIAN node must not contain a join condition");
                }
            }
            case SORT -> {
                if (node.sort().isEmpty()) {
                    throw new IllegalArgumentException("SORT node must contain SortSpec parameters");
                }
            }
            case INSERT -> {
                if (node.mutation().isEmpty()
                        || node.mutation().get().tableName() == null
                        || node.mutation().get().tableName().isBlank()
                        || node.mutation().get().insertValues().isEmpty()) {
                    throw new IllegalArgumentException("INSERT node must contain MutationSpec with tableName and insert values");
                }
            }
            case UPDATE -> {
                if (node.mutation().isEmpty()
                        || node.mutation().get().tableName() == null
                        || node.mutation().get().tableName().isBlank()
                        || node.mutation().get().updateColumn().isEmpty()
                        || node.mutation().get().updateExpression().isEmpty()) {
                    throw new IllegalArgumentException("UPDATE node must contain MutationSpec with tableName, updateColumn, and updateExpression");
                }
            }
            case DELETE -> {
                if (node.mutation().isEmpty() && node.scan().isEmpty()) {
                    throw new IllegalArgumentException("DELETE node must specify target table");
                }
                String targetTable = node.mutation().map(MutationSpec::tableName)
                        .orElseGet(() -> node.scan().map(sqlike.shared.ScanSpec::tableName).orElse(""));
                if (targetTable == null || targetTable.isBlank()) {
                    throw new IllegalArgumentException("DELETE node must specify a non-blank target tableName");
                }
            }
            case CREATE_INDEX -> {
                if (node.createIndex().isEmpty()
                        || node.createIndex().get().indexName() == null
                        || node.createIndex().get().indexName().isBlank()
                        || node.createIndex().get().tableName() == null
                        || node.createIndex().get().tableName().isBlank()
                        || node.createIndex().get().columnName() == null
                        || node.createIndex().get().columnName().isBlank()) {
                    throw new IllegalArgumentException("CREATE_INDEX node must contain valid IndexMetadata parameters");
                }
            }
            case LIMIT -> {
                if (node.limit().isEmpty() || node.limit().get().count() < 0) {
                    throw new IllegalArgumentException("LIMIT node must contain non-negative LimitSpec parameters");
                }
            }
            case CREATE_TABLE -> {
                if (node.createTable().isEmpty()
                        || node.createTable().get().tableName() == null
                        || node.createTable().get().tableName().isBlank()
                        || node.createTable().get().columns() == null
                        || node.createTable().get().columns().isEmpty()) {
                    throw new IllegalArgumentException("CREATE_TABLE node must contain valid CreateTableSpec parameters");
                }
            }
            case DROP_TABLE -> {
                if (node.dropTable().isEmpty()
                        || node.dropTable().get().tableName() == null
                        || node.dropTable().get().tableName().isBlank()) {
                    throw new IllegalArgumentException("DROP_TABLE node must contain valid DropTableSpec parameters");
                }
            }
        }
        node.predicate().ifPresent(expr -> validateExpression(expr, path));
        node.join().flatMap(JoinSpec::condition)
                .ifPresent(expr -> validateExpression(expr, path));
        node.mutation().flatMap(MutationSpec::updateExpression)
                .ifPresent(expr -> validateExpression(expr, path));
    }

    private void validateExpression(Expression expr, Set<PlanNode> path) {
        if (expr == null) {
            throw new IllegalArgumentException("Expression cannot be null");
        }
        if (expr.kind() == null) {
            throw new IllegalArgumentException("ExpressionKind cannot be null");
        }
        if ((expr.kind() == ExpressionKind.LITERAL || expr.kind() == ExpressionKind.COLUMN) && !expr.operands().isEmpty()) {
            throw new IllegalArgumentException("Leaf expression must not have operands");
        }
        switch (expr.kind()) {
            case LITERAL -> {
                if (expr.literal().isEmpty()) {
                    throw new IllegalArgumentException("LITERAL expression must contain a Value");
                }
            }
            case COLUMN -> {
                var col = expr.column().orElseThrow(
                        () -> new IllegalArgumentException("COLUMN expression must contain a ColumnRef"));
                if (col.columnName() == null || col.columnName().isBlank()) {
                    throw new IllegalArgumentException("COLUMN expression columnName cannot be blank");
                }
            }
            case UNARY -> {
                if (expr.operator().isEmpty()) {
                    throw new IllegalArgumentException("UNARY expression must contain an operator");
                }
                if (expr.operator().get() != sqlike.shared.ExpressionOperator.NEGATE) {
                    throw new IllegalArgumentException("UNARY expression requires NEGATE");
                }
                if (expr.operands().size() != 1 || expr.operands().get(0) == null) {
                    throw new IllegalArgumentException("UNARY expression must contain 1 non-null operand");
                }
            }
            case BINARY -> {
                if (expr.operator().isEmpty()) {
                    throw new IllegalArgumentException("BINARY expression must contain an operator");
                }
                if (expr.operator().get() == sqlike.shared.ExpressionOperator.NEGATE) {
                    throw new IllegalArgumentException("BINARY expression cannot use NEGATE");
                }
                if (expr.operands().size() != 2 || expr.operands().get(0) == null || expr.operands().get(1) == null) {
                    throw new IllegalArgumentException("BINARY expression must contain 2 non-null operands");
                }
            }
            case SUBQUERY -> {
                var sub = expr.subquery().orElseThrow(
                        () -> new IllegalArgumentException("SUBQUERY expression must contain SubquerySpec"));
                int expectedOperands = sub.type() == SubqueryType.IN ? 1 : 0;
                if (expr.operands().size() != expectedOperands) {
                    throw new IllegalArgumentException(
                            "SUBQUERY " + sub.type() + " expects " + expectedOperands + " operand(s)");
                }
                validateNode(sub.root(), path);
            }
        }
        for (var operand : expr.operands()) {
            validateExpression(operand, path);
        }
    }

    private int expectedArity(PlanNodeKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("PlanNodeKind cannot be null");
        }
        return switch (kind) {
            case SCAN, INDEX_SCAN, INSERT, CREATE_INDEX, CREATE_TABLE, DROP_TABLE -> 0;
            case FILTER, PROJECT, SORT, UPDATE, DELETE, LIMIT -> 1;
            case JOIN, CARTESIAN -> 2;
        };
    }

    private void validateColumnReferences(PlanNode node) {
        if (node.projection() != null) {
            for (var ref : node.projection()) {
                if (ref == null || ref.columnName() == null || ref.columnName().isBlank()) {
                    throw new IllegalArgumentException("Projection column reference cannot be empty");
                }
            }
        }
        if (node.sort().isPresent()) {
            var col = node.sort().get().column();
            if (col == null || col.columnName() == null || col.columnName().isBlank()) {
                throw new IllegalArgumentException("Sort column reference cannot be empty");
            }
        }
        if (node.mutation().isPresent() && node.mutation().get().updateColumn().isPresent()) {
            var col = node.mutation().get().updateColumn().get();
            if (col.columnName() == null || col.columnName().isBlank()) {
                throw new IllegalArgumentException("Update column reference cannot be empty");
            }
        }
    }
}
