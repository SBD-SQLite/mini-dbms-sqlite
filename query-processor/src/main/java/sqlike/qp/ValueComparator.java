package sqlike.qp;

import sqlike.shared.DataType;
import sqlike.shared.Value;

/**
 * Concrete ValueComparator implementation matching CLASS DIAGRAM FIX.
 * Supports numeric comparisons (INTEGER, FLOAT with numeric promotion) and string comparisons (CHAR, VARCHAR).
 */
public class ValueComparator implements IValueComparator {

    public ValueComparator() {
        // TODO(M1-MOCK): Default constructor
    }

    @Override
    public int compare(Value left, Value right) {
        // TODO(M1-MOCK): Compare two Values according to type semantics
        if (left == null || right == null) {
            throw new IllegalArgumentException("Cannot compare null Value objects");
        }

        if (isNumeric(left.type()) && isNumeric(right.type())) {
            return compareNumbers(left, right);
        }

        if (isText(left.type()) && isText(right.type())) {
            String leftStr = left.textValue().orElse("");
            String rightStr = right.textValue().orElse("");
            return compareUtf8(leftStr, rightStr);
        }

        throw new IllegalArgumentException("Incompatible types for comparison: " + left.type() + " vs " + right.type());
    }

    @Override
    public boolean equal(Value left, Value right) {
        // TODO(M1-MOCK): Check equality of two Values
        if (left == null || right == null) {
            return left == right;
        }
        return compare(left, right) == 0;
    }

    private int compareNumbers(Value left, Value right) {
        // TODO(M1-MOCK): Numeric comparison with promotion to double
        double leftNum = left.type() == DataType.INTEGER
                ? left.integerValue().orElse(0)
                : left.floatValue().orElse(0.0);
        double rightNum = right.type() == DataType.INTEGER
                ? right.integerValue().orElse(0)
                : right.floatValue().orElse(0.0);
        return Double.compare(leftNum, rightNum);
    }

    private int compareUtf8(String left, String right) {
        // TODO(M1-MOCK): Unicode text comparison
        return left.compareTo(right);
    }

    private boolean isNumeric(DataType type) {
        return type == DataType.INTEGER || type == DataType.FLOAT;
    }

    private boolean isText(DataType type) {
        return type == DataType.CHAR || type == DataType.VARCHAR;
    }
}
