package sqlike.qp;

import java.util.List;
import sqlike.shared.ColumnRef;

/**
 * Specification for projection operator carrying target column references, matching CLASS DIAGRAM FIX.
 */
public record ProjectSpec(List<ColumnRef> columns) {
    public ProjectSpec {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
