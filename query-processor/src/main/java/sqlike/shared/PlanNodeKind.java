package sqlike.shared;

public enum PlanNodeKind {
    SCAN,
    INDEX_SCAN,
    FILTER,
    PROJECT,
    JOIN,
    CARTESIAN,
    SORT,
    INSERT,
    UPDATE,
    DELETE,
    CREATE_INDEX
}
