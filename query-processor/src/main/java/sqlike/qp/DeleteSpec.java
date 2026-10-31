package sqlike.qp;

import sqlike.shared.MutationSpec;

/**
 * Specification for delete executor carrying target table name, matching CLASS DIAGRAM FIX.
 */
public record DeleteSpec(String tableName) {
    public DeleteSpec {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name cannot be null or blank in DeleteSpec");
        }
    }

    public static DeleteSpec fromMutationSpec(MutationSpec mutation) {
        if (mutation == null) {
            throw new IllegalArgumentException("MutationSpec cannot be null");
        }
        return new DeleteSpec(mutation.tableName());
    }
}
