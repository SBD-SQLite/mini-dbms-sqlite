package sqlike.qp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.QueryPlan;

/**
 * PlanValidator validates query plan trees for structural integrity, arity, parameters,
 * and column references matching CLASS DIAGRAM FIX.
 */
public class PlanValidator {

    public PlanValidator() {
        // TODO(M1-MOCK): Default constructor
    }

    public void validate(PreparedStatement statement) {
        // TODO(M1-MOCK): Validate query plan inside prepared statement
        if (statement == null) {
            throw new IllegalArgumentException("PreparedStatement cannot be null");
        }
        validate(statement.plan());
    }

    public void validate(QueryPlan plan) {
        // TODO(M1-MOCK): Validate QueryPlan root node
        if (plan == null) {
            throw new IllegalArgumentException("QueryPlan cannot be null");
        }
        validate(plan.root());
    }

    public void validate(PlanNode root) {
        // TODO(M1-MOCK): Validate node tree from root
        if (root == null) {
            throw new IllegalArgumentException("PlanNode root cannot be null");
        }
        Set<PlanNode> path = Collections.newSetFromMap(new IdentityHashMap<>());
        validateNode(root, path);
    }

    private void validateNode(PlanNode node, Set<PlanNode> path) {
        // TODO(M1-MOCK): Validate single node and cycle detection
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

        validateParameters(node);
        validateColumnReferences(node);

        for (PlanNode child : node.children()) {
            validateNode(child, path);
        }

        path.remove(node);
    }

    private void validateParameters(PlanNode node) {
        // TODO(M1-MOCK): Validate node parameters matching PlanNodeKind
        switch (node.kind()) {
            case SCAN -> {
                if (node.scan().isEmpty()) {
                    throw new IllegalArgumentException("SCAN node must contain ScanSpec parameters");
                }
            }
            case INDEX_SCAN -> {
                if (node.scan().isEmpty() || node.scan().get().indexName().isEmpty()) {
                    throw new IllegalArgumentException("INDEX_SCAN node must contain ScanSpec with indexName");
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
            }
            case CARTESIAN -> {
                // CARTESIAN can carry JoinSpec with CARTESIAN JoinType or empty if implied
            }
            case SORT -> {
                if (node.sort().isEmpty()) {
                    throw new IllegalArgumentException("SORT node must contain SortSpec parameters");
                }
            }
            case INSERT -> {
                if (node.mutation().isEmpty() || node.mutation().get().insertValues().isEmpty()) {
                    throw new IllegalArgumentException("INSERT node must contain MutationSpec with insert values");
                }
            }
            case UPDATE -> {
                if (node.mutation().isEmpty() || node.mutation().get().updateColumn().isEmpty() || node.mutation().get().updateExpression().isEmpty()) {
                    throw new IllegalArgumentException("UPDATE node must contain MutationSpec with updateColumn and updateExpression");
                }
            }
            case DELETE -> {
                if (node.mutation().isEmpty() && node.scan().isEmpty()) {
                    throw new IllegalArgumentException("DELETE node must specify target table");
                }
            }
            case CREATE_INDEX -> {
                if (node.createIndex().isEmpty()) {
                    throw new IllegalArgumentException("CREATE_INDEX node must contain IndexMetadata parameters");
                }
            }
        }
    }

    private int expectedArity(PlanNodeKind kind) {
        // TODO(M1-MOCK): Child arity per node kind from CLASS DIAGRAM FIX invariants
        if (kind == null) {
            throw new IllegalArgumentException("PlanNodeKind cannot be null");
        }
        return switch (kind) {
            case SCAN, INDEX_SCAN, INSERT, CREATE_INDEX -> 0;
            case FILTER, PROJECT, SORT, UPDATE, DELETE -> 1;
            case JOIN, CARTESIAN -> 2;
        };
    }

    private void validateColumnReferences(PlanNode node) {
        // TODO(M1-MOCK): Validate column reference syntax
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
    }
}
