package sqlike.qp;

import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.Value;

/**
 * Concrete ValueComparator implementation matching CLASS DIAGRAM FIX and PRD §8.
 * Supports numeric comparisons (INTEGER, FLOAT with numeric promotion) and Unicode code-point string comparisons (CHAR, VARCHAR).
 */
public class ValueComparator implements IValueComparator {

    public ValueComparator() {
    }

    @Override
    public int compare(Value left, Value right) {
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
        if (left == null || right == null) {
            return left == right;
        }
        return compare(left, right) == 0;
    }

    private int compareNumbers(Value left, Value right) {
        if (left.type() == DataType.INTEGER && right.type() == DataType.INTEGER) {
            return Integer.compare(left.integerValue().orElse(0), right.integerValue().orElse(0));
        }

        double leftNum = left.type() == DataType.INTEGER
                ? left.integerValue().orElse(0)
                : left.floatValue().orElse(0.0);
        double rightNum = right.type() == DataType.INTEGER
                ? right.integerValue().orElse(0)
                : right.floatValue().orElse(0.0);
        if (Double.isNaN(leftNum) || Double.isNaN(rightNum)) {
            throw new QpException(ErrorCode.INVALID_VALUE, "Cannot compare NaN floating point value");
        }
        if (leftNum == 0.0 && rightNum == 0.0) {
            return 0;
        }
        return Double.compare(leftNum, rightNum);
    }

    private int compareUtf8(String left, String right) {
        return compareCodePoints(left, right);
    }

    private int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }

    private boolean isNumeric(DataType type) {
        return type == DataType.INTEGER || type == DataType.FLOAT;
    }

    private boolean isText(DataType type) {
        return type == DataType.CHAR || type == DataType.VARCHAR;
    }
}
