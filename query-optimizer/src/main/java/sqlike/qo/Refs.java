package sqlike.qo;

import java.util.Optional;
import sqlike.shared.ColumnRef;

/** helper buat build ColumnRef karena shared ColumnRef ga punya factory method */
public final class Refs {
    private Refs() { }
    public static ColumnRef column(String table, String column) {
        return new ColumnRef(Optional.ofNullable(table), column);
    }
}
