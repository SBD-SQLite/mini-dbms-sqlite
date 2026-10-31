package sqlike.qp;

import java.util.List;
import sqlike.shared.MutationSpec;
import sqlike.shared.Value;

/**
 * Specification for insert executor carrying target table and record values, matching CLASS DIAGRAM FIX.
 */
public record InsertSpec(String tableName, List<Value> values) {
    public InsertSpec {
        values = values == null ? List.of() : List.copyOf(values);
    }

    public static InsertSpec fromMutationSpec(MutationSpec mutation) {
        if (mutation == null) {
            throw new IllegalArgumentException("MutationSpec cannot be null");
        }
        return new InsertSpec(mutation.tableName(), mutation.insertValues());
    }
}
