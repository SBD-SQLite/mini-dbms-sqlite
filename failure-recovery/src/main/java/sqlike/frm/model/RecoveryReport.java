package sqlike.frm.model;

// Laporan status akhir setelah proses crash recovery selesai
public class RecoveryReport {
    public boolean success;
    public String errorCode;
    public String errorMessage;
    public int checkpointLsn;
    public int committedCount;
    public int undoCount;
    public int redoCount;
    public int lastLsn;
    public int maxTxId;

    // TODO: Sesuaikan constructor atau getter/setter jika diperlukan
}