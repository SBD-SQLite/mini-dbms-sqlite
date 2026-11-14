package sqlike.frm.recovery;

import sqlike.frm.log.LogRecord;
import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;

public class ChangeApplier {
    private final SmPort sm;

    public ChangeApplier(SmPort sm) {
        this.sm = sm;
    }

    public void apply(LogRecord record) throws Exception {
        if (record.change().isEmpty()) return;
        DataChange change = record.change().get();
        
        sm.writeAt(
            change.tableName(), 
            change.rid(), 
            change.after(), 
            record.lsn()
        );
    }

    public void undo(LogRecord record) throws Exception {
        if (record.change().isEmpty()) return;
        DataChange change = record.change().get();
        
        sm.writeAt(
            change.tableName(), 
            change.rid(), 
            change.before(), 
            record.lsn()
        );
    }
}