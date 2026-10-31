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

    public InsertSpec(sqlike.qp.port.QpContracts.TableId tableId, Row row) {
        this(tableId != null ? tableId.value() : "", row != null ? row.values() : List.of());
    }

    public sqlike.qp.port.QpContracts.TableId tableId() {
        return new sqlike.qp.port.QpContracts.TableId(tableName);
    }

    public Row row() {
        return new Row(values);
    }

    public static InsertSpec fromMutationSpec(MutationSpec mutation) {
        if (mutation == null) {
            throw new IllegalArgumentException("MutationSpec cannot be null");
        }
        return new InsertSpec(mutation.tableName(), mutation.insertValues());
    }
}
