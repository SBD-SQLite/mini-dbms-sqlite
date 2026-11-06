package sqlike.frm.port;

import sqlike.shared.RID;
import sqlike.shared.RecordImage;

/**
 * What FRM needs from Storage Manager; the real SM is plugged in through an adapter (DIP).
 *
 * <p>Failures are reported as unchecked exceptions so FRM never continues after a failed write.
 */
public interface SmPort {

    /** Reads the current record at {@code rid}; {@code exists == false} means no record there. */
    RecordImage readAt(String table, RID rid);

    /** Puts the record at {@code rid} into the given state, used by undo and redo (keeps the RID). */
    void writeAt(String table, RID rid, RecordImage image, long lsn);

    /** Writes every dirty page to disk (checkpoint). */
    void flushAll();

    /** Copies the current data files and returns the snapshot ID (FR-05 base snapshot). */
    String createSnapshot();

    /** Replaces the current data with a snapshot, then reloads buffer and catalog (FR-05). */
    void restoreSnapshot(String snapshotId);
}
