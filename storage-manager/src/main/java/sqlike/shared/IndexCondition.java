package sqlike.shared;

public class IndexCondition {
    // + operator: ComparisonOperator
    // + key: Value
    // + upperBound: Optional<Value>
    boolean lowerInclusive;
    boolean upperInclusive;

    public boolean getLowerInclusive() {
        return lowerInclusive;
    }

    public void setLowerInclusive(boolean lowerInclusive) {
        this.lowerInclusive = lowerInclusive;
    }

    public boolean getUpperInclusive() {
        return upperInclusive;
    }

    public void setUpperInclusive(boolean upperInclusive) {
        this.upperInclusive = upperInclusive;
    }
}
