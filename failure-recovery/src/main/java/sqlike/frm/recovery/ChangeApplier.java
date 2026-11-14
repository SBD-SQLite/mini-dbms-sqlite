package sqlike.frm.recovery;

import sqlike.frm.log.LogRecord;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;

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
        if (record.change().isEmpty()) return;
        DataChange change = record.change().get();
        sm.writeAt(change.tableName(), change.rid(), change.after(), lsn);
    }

    // undo
    public void undo(LogRecord record) throws Exception {
        undo(record, record.lsn());
    }

    public void undo(LogRecord record, long lsn) throws Exception {
        if (record.change().isEmpty()) return;
        DataChange change = record.change().get();
        sm.writeAt(change.tableName(), change.rid(), change.before(), lsn);
    }
}