package sqlike.frm.Log;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.shared.DataChange;

public class LogManager implements IWalDurability {
    private final String logPath;
    private long nextLSN;
    private long lastSavedLSN;
    private final ReentrantLock lock;
    
    public LogManager(String logPath){
        this.logPath = logPath;
        this.nextLSN = 1L;
        this.lastSavedLSN = 0L;
        this.lock = new ReentrantLock();
    }

    // mencatat awal transaksi
    // @return LSN catatan BEGIN
    public long logBegin(long txID){
        // TODO: Implementasi pencatatan log BEGIN
        return 1L;
    }

    // @return LSN catatan perubahan
    public long logChange(long txID, DataChange change){
        // TODO : Implementasi pencatatan perubahan data
        return 1L;
    }

    public void logCommit(long txID){
        // TODO : Catat log COMMIT dan mastiin log persisten
    }

    // mencatat pembatalan transaksi
    // harus mastiin undo selesai sebelum memanggil method ini
    public void logAbort(long txID){
        // TODO : Catat log ABORT setelah rollback berhasil
    }

    // CheckpointManager yang bakal nyelesaian tahapan checkpoint 
    public CheckpointInfo logCheckpoint(long flushedThroughLSN){
        // TODO : alokasi LSN sama catat penanda checkpoint selesai
        return new CheckpointInfo(1L, 0L, Instant.EPOCH);
    }

    @Override 
    public void ensureDurable(long lsn){
        // TODO : Mastiin seluruh catatan sampai lsn udah kesimpen permanen
        // TODO : perbaru lastSavedLSN
    }

    public List<LogRecord> readLogs(Long txID){
        // TODO : Baca log berurutan dari LSN terkecil, null berarti semua
        return List.of();
    }

    public String showLogs(Long txID){
        // TODO : Baca log berurutan dari LSN terkecil, null berarti semua. Gunain readLogs
        return "";
    }

    // Membaca catatan transaksi menurut urutan LSN menurun.
    // digunain bagian rollback
    public List<LogRecord> readLogsBackward(long txId) {
        // TODO: Baca catatan transaksi dari yang terbaru.
        return List.of();
    }

    public long getLastLsn() {
        // TODO : Kembaliin LSN terakhir
        return 0L;
    }

    public String archiveActiveLog(){
        // TODO : Implementasi log aktif dan return lokasi archive
        return "";
    }

    public void startNewLog(){
        // TODO: Buka log aktif baru.
        // TODO : Pertahanin kelanjutan LSN
    }
}
