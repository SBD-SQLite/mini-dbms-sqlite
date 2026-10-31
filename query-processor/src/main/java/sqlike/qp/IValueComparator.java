package sqlike.qp;

import sqlike.shared.Value;

/**
 * ValueComparator contract interface matching CLASS DIAGRAM FIX.
 */
public interface IValueComparator {
    /**
     * Compares two Values according to type-specific ordering rules.
     *
     * @param left left operand
     * @param right right operand
     * @return negative if left < right, 0 if equal, positive if left > right
     */
    int compare(Value left, Value right);

    /**
     * Checks if two Values are equal according to type-specific equivalence rules.
     *
     * @param left left operand
     * @param right right operand
     * @return true if left and right are equivalent
     */
    boolean equal(Value left, Value right);
}
