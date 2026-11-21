package sqlike.frm.recovery;

import java.util.List;

import sqlike.frm.log.LogRecord;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;
import sqlike.shared.SchemaImage;

public class ChangeApplier {
    private final SmPort sm;

    public ChangeApplier(SmPort sm) {
        this.sm = sm;
    }

    // redo
    public void apply(LogRecord record) throws Exception {
        apply(record, record.lsn());
    }

    public void apply(LogRecord record, long lsn) throws Exception {
        if (record.change().isPresent()) {
            DataChange change = record.change().get();
            sm.writeAt(change.tableName(), change.rid(), change.after(), lsn);
        } else if (record.schemaChange().isPresent()) {
            List<SchemaImage> after = record.schemaChange().get().after();
            for (SchemaImage image : after) {
                sm.restoreSchema(image, lsn);
            }
        }
    }

    // undo
    public void undo(LogRecord record) throws Exception {
        undo(record, record.lsn());
    }

    public void undo(LogRecord record, long lsn) throws Exception {
        if (record.change().isPresent()) {
            DataChange change = record.change().get();
            sm.writeAt(change.tableName(), change.rid(), change.before(), lsn);
        } else if (record.schemaChange().isPresent()) {
            List<SchemaImage> before = record.schemaChange().get().before();
            for (int i = before.size() - 1; i >= 0; i--) {
                sm.restoreSchema(before.get(i), lsn);
            }
        }
    }
}
