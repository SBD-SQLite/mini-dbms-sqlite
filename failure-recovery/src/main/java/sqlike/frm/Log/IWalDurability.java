package sqlike.frm.Log;

public interface IWalDurability {
    // mastiin seluruh catatan sampai lsn udah kesimpen permanen
    void ensureDurable(long lsn);
}
