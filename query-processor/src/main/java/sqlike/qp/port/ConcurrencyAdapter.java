package sqlike.qp.port;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import sqlike.contract.IConcurrencyControlManager;
import sqlike.contract.IFailureRecoveryManager;
import sqlike.qp.QpException;
import sqlike.qp.port.QpContracts.AccessDecision;
import sqlike.qp.port.QpContracts.AccessGrant;
import sqlike.qp.port.QpContracts.AccessMode;
import sqlike.qp.port.QpContracts.CancellationToken;
import sqlike.qp.port.QpContracts.ChangeId;
import sqlike.qp.port.QpContracts.ChangeRecord;
import sqlike.qp.port.QpContracts.IConcurrencyControl;
import sqlike.qp.port.QpContracts.Lsn;
import sqlike.qp.port.QpContracts.RecoveryReport;
import sqlike.qp.port.QpContracts.ResourceKey;
import sqlike.qp.port.QpContracts.SessionId;
import sqlike.qp.port.QpContracts.TransactionState;
import sqlike.qp.port.QpContracts.TxId;
import sqlike.qp.port.QpContracts.WriteLease;
import sqlike.qp.port.QpContracts.WritePermit;
import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ErrorCode;
import sqlike.shared.ResourceId;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;
import static sqlike.qp.port.QpContracts.failure;

/**
 * Adapts the Supergroup contracts ({@link IConcurrencyControlManager} and {@link IFailureRecoveryManager})
 * to the internal QP port {@link IConcurrencyControl}.
 */
public final class ConcurrencyAdapter implements IConcurrencyControl {
    private final IConcurrencyControlManager ccm;
    private final IFailureRecoveryManager frm;
    private final Map<TxId, TransactionState> states = new ConcurrentHashMap<>();
    private final Map<TxId, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final Map<ChangeId, WritePermit> permits = new ConcurrentHashMap<>();
    private final Set<ChangeId> leases = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AccessGrant> pendingWaits = new ConcurrentHashMap<>();
    private final AtomicLong fallbackLsn = new AtomicLong();

    public ConcurrencyAdapter(IConcurrencyControlManager ccm, IFailureRecoveryManager frm) {
        this.ccm = Objects.requireNonNull(ccm, "IConcurrencyControlManager cannot be null");
        this.frm = frm;
    }

    @Override
    public void initialize(RecoveryReport report) {
        if (report == null || !report.success()) {
            throw failure("Recovery failed");
        }
        states.clear();
        tokens.clear();
        permits.clear();
        leases.clear();
        pendingWaits.clear();
        ccm.initAfterRecovery(report.lastTxId());
    }

    @Override
    public TxId begin(SessionId sessionId) {
        TransactionResponse response = ccm.beginTransaction();
        if (response == null) {
            throw failure("CCM returned null beginTransaction response");
        }
        if (response.error().isPresent()) {
            throw new QpException(response.error().get());
        }
        if (response.status() != TransactionStatus.ACTIVE) {
            throw failure("Transaction failed to start: " + response.status());
        }
        TxId tx = new TxId(response.txId());
        states.put(tx, TransactionState.ACTIVE);
        tokens.put(tx, new CancellationToken());
        return tx;
    }

    @Override
    public AccessDecision acquireAccess(TxId txId, ResourceKey resource, AccessMode mode) {
        if (getState(txId) != TransactionState.ACTIVE) {
            throw failure("Transaction is not active: " + txId.value());
        }
        ResourceId rid = resource.rid().isPresent()
                ? ResourceId.ofRecord(resource.tableId().value(), resource.rid().get().value())
                : ResourceId.ofTable(resource.tableId().value());
        AccessGrant grant = new AccessGrant(txId, resource, mode);
        UUID reqId = UUID.randomUUID();
        if (mode == AccessMode.READ) {
            AccessResponse resp = ccm.requestRead(txId.value(), rid);
            if (resp == null) {
                throw failure("CCM returned null AccessResponse");
            }
            if (resp.error().isPresent()) {
                if (resp.error().get().code() == ErrorCode.TRANSACTION_ABORTED
                        || resp.status() == AccessStatus.ABORTED) {
                    states.put(txId, TransactionState.ABORTED);
                    getCancellationToken(txId).cancel();
                }
                throw new QpException(resp.error().get());
            }
            if (resp.status() == AccessStatus.WAIT) {
                pendingWaits.put(reqId, grant);
                return new AccessDecision(AccessStatus.WAIT, Optional.empty(), reqId);
            }
            if (resp.status() == AccessStatus.ABORTED) {
                states.put(txId, TransactionState.ABORTED);
                getCancellationToken(txId).cancel();
                return new AccessDecision(AccessStatus.ABORTED, Optional.empty(), reqId);
            }
        }
        return new AccessDecision(AccessStatus.GRANTED, Optional.of(grant), reqId);
    }

    @Override
    public AccessDecision waitAccess(UUID requestId) {
        AccessGrant grant = Optional.ofNullable(pendingWaits.remove(requestId))
                .orElseThrow(() -> failure("Unknown wait request ID: " + requestId));
        if (getState(grant.txId()) != TransactionState.ACTIVE) {
            return new AccessDecision(AccessStatus.ABORTED, Optional.empty(), requestId);
        }
        return acquireAccess(grant.txId(), grant.resource(), grant.mode());
    }

    @Override
    public WritePermit authorizeChange(ChangeRecord change) {
        if (getState(change.txId()) != TransactionState.ACTIVE) {
            throw failure("Transaction not active: " + change.txId().value());
        }
        AccessResponse resp = ccm.requestWrite(change.txId().value(), change.value());
        if (resp != null && resp.error().isPresent()) {
            if (resp.error().get().code() == ErrorCode.TRANSACTION_ABORTED
                    || resp.status() == AccessStatus.ABORTED) {
                states.put(change.txId(), TransactionState.ABORTED);
                getCancellationToken(change.txId()).cancel();
            }
            throw new QpException(resp.error().get());
        }
        if (resp != null && resp.status() == AccessStatus.ABORTED) {
            states.put(change.txId(), TransactionState.ABORTED);
            getCancellationToken(change.txId()).cancel();
            throw failure("CCM aborted transaction on requestWrite");
        }
        long lsn = (resp != null && resp.changeLSN().isPresent())
                ? resp.changeLSN().get()
                : (frm != null
                        ? frm.logChange(change.txId().value(), change.value())
                        : fallbackLsn.incrementAndGet());
        WritePermit permit = new WritePermit(change.id(), new Lsn(lsn));
        permits.put(change.id(), permit);
        return permit;
    }

    @Override
    public WriteLease acquireApplyLease(WritePermit permit) {
        if (getState(permit.changeId().txId()) != TransactionState.ACTIVE) {
            throw failure("Transaction not active");
        }
        if (!permit.equals(permits.get(permit.changeId())) || !leases.add(permit.changeId())) {
            throw failure("Invalid apply lease");
        }
        return () -> leases.remove(permit.changeId());
    }

    @Override
    public void confirmApplied(ChangeId changeId) {
        if (!leases.contains(changeId)) {
            throw failure("Missing apply lease for " + changeId);
        }
        permits.remove(changeId);
    }

    @Override
    public TransactionState getState(TxId txId) {
        return states.getOrDefault(txId, TransactionState.ACTIVE);
    }

    @Override
    public CancellationToken getCancellationToken(TxId txId) {
        return tokens.computeIfAbsent(txId, k -> new CancellationToken());
    }

    @Override
    public TransactionState commit(TxId txId) {
        TransactionResponse resp = ccm.commit(txId.value());
        if (resp == null) {
            states.put(txId, TransactionState.FAILED);
            return TransactionState.FAILED;
        }
        if (resp.status() == TransactionStatus.COMMITTED) {
            states.put(txId, TransactionState.COMMITTED);
            return TransactionState.COMMITTED;
        }
        if (resp.status() == TransactionStatus.ABORTED) {
            states.put(txId, TransactionState.ABORTED);
            getCancellationToken(txId).cancel();
            return TransactionState.ABORTED;
        }
        states.put(txId, TransactionState.FAILED);
        return TransactionState.FAILED;
    }

    @Override
    public TransactionState abort(TxId txId, String reason) {
        TransactionResponse resp = ccm.abort(txId.value());
        states.put(txId, TransactionState.ABORTED);
        getCancellationToken(txId).cancel();
        permits.keySet().removeIf(id -> id.txId().equals(txId));
        leases.removeIf(id -> id.txId().equals(txId));
        if (resp != null && resp.status() == TransactionStatus.ABORTED) {
            return TransactionState.ABORTED;
        }
        return TransactionState.ABORTED;
    }

    public void registerExternalTransaction(long txId) {
        TxId id = new TxId(txId);
        states.putIfAbsent(id, TransactionState.ACTIVE);
        tokens.putIfAbsent(id, new CancellationToken());
    }
}
