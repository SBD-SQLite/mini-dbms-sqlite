package sqlike.frm.recovery;

import sqlike.frm.Log.LogRecord;
import sqlike.frm.model.RecoveryPlan;
import java.util.List;

/**
 * FR5: Bertanggung jawab melakukan fase ANALYSIS.
 * Membaca log mundur/maju, mencari checkpoint terakhir, dan menentukan status transaksi.
 */
public class RecoveryAnalyzer {

    public RecoveryAnalyzer() {
        // TODO: Inisialisasi jika diperlukan
    }

    /**
     * Menganalisis seluruh catatan log untuk membangun RecoveryPlan.
     */
    public RecoveryPlan analyze(List<LogRecord> records) {
        // TODO: Panggil findLastCheckpoint() dan classify()
        // TODO: Tentukan startLsn (dari checkpoint + 1, atau awal log jika tidak ada)
        throw new UnsupportedOperationException("TODO: Implementasi analyze()");
    }

    /**
     * Mencari penanda CHECKPOINT terakhir yang selesai.
     */
    private LogRecord findLastCheckpoint(List<LogRecord> records) {
        // TODO: Iterasi log dari belakang, cari LogType.CHECKPOINT
        throw new UnsupportedOperationException("TODO: Implementasi findLastCheckpoint()");
    }

    /**
     * Mengklasifikasikan transaksi: committed, aborted, atau uncommitted (aktif saat crash).
     */
    private RecoveryPlan classify(List<LogRecord> records, int startLsn) {
        // TODO: Tentukan transaksi mana yang punya COMMIT, ABORT, atau tidak punya keduanya.
        // TODO: Transaksi tanpa COMMIT/ABORT adalah uncommitted, dan masuk ke dalam list undoChanges.
        // TODO: Transaksi committed masuk ke list redoChanges.
        // TODO: Cari maxTxId tertinggi dari seluruh log.
        throw new UnsupportedOperationException("TODO: Implementasi classify()");
    }
}