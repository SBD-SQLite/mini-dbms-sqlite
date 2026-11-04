package sqlike.qp;

/**
 * Discriminator for binary protocol frames between QueryServer and CliClient.
 */
public enum FrameKind {
    SCHEMA,
    ROW,
    SUMMARY
}

