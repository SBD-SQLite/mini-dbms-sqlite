package sqlike.contract;

/**
 * Durability gate that Storage Manager calls before writing a dirty page (PRD v1.0 §3.5, §11).
 *
 * <p>SM keeps {@code pageLSN = max(pageLSN, lsn)} and calls {@link #ensureDurable} with that value
 * before writing data, index, or catalog pages to disk, including on eviction. The FRM facade
 * implements this interface, so SM depends on this contract and never on an FRM class.
 *
 * <p>Temporary location: moves to the root {@code contracts/} source set once it exists.
 */
public interface IWalDurability {

    /**
     * Returns only after every log record up to and including {@code lsn} is permanently stored.
     * Throws an unchecked exception on failure; the caller must then keep the page dirty.
     */
    void ensureDurable(long lsn);
}
