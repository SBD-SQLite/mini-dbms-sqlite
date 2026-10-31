package sqlike.qp;

import sqlike.shared.IndexMetadata;

/**
 * Specification for create index executor carrying index metadata, matching CLASS DIAGRAM FIX.
 */
public record CreateIndexSpec(IndexMetadata definition) {
    public CreateIndexSpec {
        if (definition == null) {
            throw new IllegalArgumentException("Index definition cannot be null in CreateIndexSpec");
        }
    }
}
