package sqlike.qp;

import sqlike.shared.RID;

/**
 * Physical record retrieved from or stored in the storage manager.
 */
public record StoredRecord(RID rid, Row row) {
    public StoredRecord {
        if (rid == null) {
            throw new IllegalArgumentException("RID cannot be null in StoredRecord");
        }
        if (row == null) {
            throw new IllegalArgumentException("Row cannot be null in StoredRecord");
        }
    }
}
