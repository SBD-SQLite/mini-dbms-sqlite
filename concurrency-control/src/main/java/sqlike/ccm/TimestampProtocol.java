package sqlike.ccm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import sqlike.shared.AccessStatus;
import sqlike.shared.ProtocolType;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionStatus;

public class TimestampProtocol implements IConcurrencyProtocol {

    private static final long WAKE_INTERVAL_MS = 100L;
    private static final long NO_WRITER = 0L;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<ResourceId, Entry> timestamps = new HashMap<>();
    private final Map<Long, Set<ResourceId>> writtenBy = new HashMap<>();
    private final Map<Long, Set<ResourceId>> readBy = new HashMap<>();
    private final boolean blockOnConflict;
    private final boolean strictReads;

    public TimestampProtocol() {
        this(true, true);
    }

    public TimestampProtocol(boolean blockOnConflict, boolean strictReads) {
        this.blockOnConflict = blockOnConflict;
        this.strictReads = strictReads;
    }

    @Override
    public ProtocolType getType() {
        return ProtocolType.TIMESTAMP_BASED;
    }

    @Override
    public void onBegin(Transaction tx) {
        Objects.requireNonNull(tx, "tx");
    }

    @Override
    public AccessStatus checkRead(Transaction tx, ResourceId resource) {
        return check(tx, resource, false);
    }

    @Override
    public AccessStatus checkWrite(Transaction tx, ResourceId resource) {
        return check(tx, resource, true);
    }

    @Override
    public void release(Transaction tx) {
        Objects.requireNonNull(tx, "tx");
        long txId = tx.getTxId();
        TransactionStatus status = tx.getStatus();
        boolean rolledBack = status == TransactionStatus.ABORTED || status == TransactionStatus.FAILED;

        lock.lock();
        try {
            Set<ResourceId> written = writtenBy.remove(txId);
            if (written != null) {
                for (ResourceId resource : written) {
                    Entry entry = timestamps.get(resource);
                    if (entry == null || entry.writerTxId != txId) {
                        continue;
                    }
                    if (!rolledBack) {
                        entry.committedWriteTs = Math.max(entry.committedWriteTs, entry.writerTs);
                    }
                    entry.writerTxId = NO_WRITER;
                    entry.writerTs = 0L;
                    entry.changed.signalAll();
                }
            }
            Set<ResourceId> read = readBy.remove(txId);
            if (read != null) {
                for (ResourceId resource : read) {
                    Entry entry = timestamps.get(resource);
                    if (entry != null && entry.readers.remove(txId) != null) {
                        entry.changed.signalAll();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            for (Entry entry : timestamps.values()) {
                entry.changed.signalAll();
            }
            timestamps.clear();
            writtenBy.clear();
            readBy.clear();
        } finally {
            lock.unlock();
        }
    }

    private AccessStatus check(Transaction tx, ResourceId resource, boolean write) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(resource, "resource");
        long txId = tx.getTxId();
        long ts = tx.getTimestamp();

        lock.lock();
        try {
            while (true) {
                if (!tx.isActive()) {
                    return AccessStatus.ABORTED;
                }

                Entry entry = timestamps.computeIfAbsent(resource, r -> new Entry());

                if (ts < entry.effectiveWriteTs() || (write && ts < entry.readTs)) {
                    return AccessStatus.ABORTED;
                }

                boolean mustWait = false;
                if (entry.writerTxId != NO_WRITER && entry.writerTxId != txId) {
                    if (ts <= entry.writerTs) {
                        return AccessStatus.ABORTED;
                    }
                    mustWait = true;
                }
                if (write && strictReads) {
                    for (Map.Entry<Long, Long> reader : entry.readers.entrySet()) {
                        if (reader.getKey().longValue() == txId) {
                            continue;
                        }
                        if (ts <= reader.getValue()) {
                            return AccessStatus.ABORTED;
                        }
                        mustWait = true; 
                    }
                }

                if (mustWait) {
                    if (!blockOnConflict) {
                        return AccessStatus.WAIT;
                    }
                    try {
                        entry.changed.await(WAKE_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return AccessStatus.ABORTED;
                    }
                    continue; 
                }

                if (write) {
                    entry.writerTxId = txId;
                    entry.writerTs = ts;
                    writtenBy.computeIfAbsent(txId, id -> new HashSet<>()).add(resource);
                } else {
                    if (ts > entry.readTs) {
                        entry.readTs = ts;
                    }
                    if (strictReads && entry.writerTxId != txId) {
                        entry.readers.put(txId, ts);
                        readBy.computeIfAbsent(txId, id -> new HashSet<>()).add(resource);
                    }
                }
                return AccessStatus.GRANTED;
            }
        } finally {
            lock.unlock();
        }
    }

    private final class Entry {
        long readTs;
        long committedWriteTs;
        long writerTxId = NO_WRITER;
        long writerTs;
        final Map<Long, Long> readers = new HashMap<>();
        final Condition changed = lock.newCondition();

        long effectiveWriteTs() {
            return writerTxId != NO_WRITER ? writerTs : committedWriteTs;
        }
    }
}