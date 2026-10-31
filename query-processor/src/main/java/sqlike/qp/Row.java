package sqlike.qp;

import java.util.List;
import sqlike.shared.Value;

/**
 * Logical row consisting of an ordered list of typed Values.
 */
public record Row(List<Value> values) {
    public Row {
        values = values == null ? List.of() : List.copyOf(values);
    }

    public static Row of(List<Value> values) {
        return new Row(values);
    }

    public static Row of(Value... values) {
        return new Row(values == null ? List.of() : List.of(values));
    }

    public Value getValue(int offset) {
        if (offset < 0 || offset >= values.size()) {
            throw new IndexOutOfBoundsException("Column offset out of bounds: " + offset + ", row size: " + values.size());
        }
        return values.get(offset);
    }

    public int size() {
        return values.size();
    }
}
