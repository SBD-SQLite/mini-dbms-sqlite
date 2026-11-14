package sqlike.qo;

import java.util.Optional;
import sqlike.shared.DataType;
import sqlike.shared.Value;

/** Helper to build Value literals karena the shared Value record has no factory methods */
public final class Literals {
    private Literals() { }
    public static Value integer(int v) { return new Value(DataType.INTEGER, Optional.of(v), Optional.empty(), Optional.empty()); }
    public static Value floating(double v) { return new Value(DataType.FLOAT, Optional.empty(), Optional.of(v), Optional.empty()); }
}