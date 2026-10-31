package sqlike.frm.log;

public interface IWalDurability {
    // mastiin seluruh catatan sampai lsn udah kesimpen permanen
    void ensureDurable(long lsn);
}
