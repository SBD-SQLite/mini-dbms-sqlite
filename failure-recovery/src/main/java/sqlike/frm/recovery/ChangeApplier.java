package sqlike.frm.recovery;

import sqlike.frm.port.SmPort;
import sqlike.shared.DataChange;

/**
 * FR5: Bertanggung jawab mengeksekusi perubahan ke Storage Manager (Fase REDO / UNDO).
 */
public class ChangeApplier {
    private SmPort sm;

    public ChangeApplier(SmPort sm) {
        this.sm = sm;
    }

    /**
     * REDO: Menerapkan after-image ke Storage Manager.
     * Harus idempoten (aman jika dieksekusi berulang kali).
     */
    public void apply(DataChange change, int lsn) {
        // TODO: Panggil sm.write_at() menggunakan nilai after-image.
        // TODO: Pastikan redo UPDATE menjadi nilai baru (bukan menambah nilai).
        // TODO: Pastikan redo INSERT tidak membuat data ganda.
        throw new UnsupportedOperationException("TODO: Implementasi apply()");
    }

    /**
     * UNDO: Mengembalikan data ke before-image (Rollback).
     */
    public void undo(DataChange change, int lsn) {
        // TODO: Panggil sm.write_at() menggunakan nilai before-image.
        // TODO: Undo DELETE harus mengembalikan record ke RID yang sama.
        // TODO: Undo INSERT harus menghapus record tersebut.
        throw new UnsupportedOperationException("TODO: Implementasi undo()");
    }
}