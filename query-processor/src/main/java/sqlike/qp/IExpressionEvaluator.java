package sqlike.qp;

import sqlike.shared.Expression;

/**
 * ExpressionEvaluator contract interface matching CLASS DIAGRAM FIX.
 */
public interface IExpressionEvaluator {
    /**
     * Evaluates an expression tree against a row and schema, returning the computed ExpressionResult.
     *
     * @param expr expression to evaluate
     * @param row input row values
     * @param schema schema describing column positions and types
     * @return evaluated ExpressionResult
     */
    ExpressionResult evaluate(Expression expr, Row row, Schema schema);

    /**
     * Evaluates a boolean condition against a row and schema.
     *
     * @param expr expression representing a predicate condition
     * @param row input row values
     * @param schema schema describing column positions and types
     * @return boolean truth value
     */
    boolean evaluateCondition(Expression expr, Row row, Schema schema);
}
