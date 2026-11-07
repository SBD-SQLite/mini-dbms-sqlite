package sqlike.replacement_durability;
 
/**
 * The ONLY thing the SM needs from the Failure Recovery Manager for buffering.
 * Contract: when this returns normally, every log record with LSN <= lsn is durable.
 * If it cannot guarantee that, it must throw StorageException(WAL_FAILURE), never return silently.
 */
public interface IWalDurability {
    void ensureDurable(long lsn);
}