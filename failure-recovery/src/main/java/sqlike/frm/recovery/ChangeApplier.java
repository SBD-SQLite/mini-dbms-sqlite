package sqlike.frm.recovery;

import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;

public class ChangeApplier {
    private SmPort sm;

    public ChangeApplier(SmPort sm) {
        this.sm = sm;
    }

    public void apply(DataChange change, int lsn) {
        // TODO: Panggil sm.write_at() menggunakan nilai after-image
        // TODO: Redo harus IDEMPOTEN (tidak boleh membuat record ganda)
    }

    public void undo(DataChange change, int lsn) {
        // TODO: Panggil sm.write_at() menggunakan nilai before-image
        // TODO: Undo DELETE harus mengembalikan record ke RID yang sama persis
        // TODO: Undo INSERT harus menghapus record tersebut
    }
}