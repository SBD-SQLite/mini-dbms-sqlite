package sqlike.qp.port;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
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
import sqlike.qp.port.QpContracts.SchemaChangeRecord;
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
    // The counter is a compatibility fallback. WAL-backed writes need an LSN from CCM or FRM.
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
        getCancellationToken(txId).check();
        ResourceId rid = resource.tableId().value().equals("__catalog__")
                ? ResourceId.ofCatalog() : resource.rid().isPresent()
                ? ResourceId.ofRecord(resource.tableId().value(), resource.rid().get().value())
                : ResourceId.ofTable(resource.tableId().value());
        AccessGrant grant = new AccessGrant(txId, resource, mode);
        UUID reqId = UUID.randomUUID();
        AccessResponse response = checkedAccessResponse(txId, mode == AccessMode.READ
                ? ccm.requestRead(txId.value(), rid)
                : ccm.requestWriteAccess(txId.value(), rid));
        if (response.status() == AccessStatus.GRANTED) getCancellationToken(txId).check();
        return switch (response.status()) {
            case GRANTED -> new AccessDecision(AccessStatus.GRANTED, Optional.of(grant), reqId);
            case WAIT -> throw failure("CCM must block until GRANTED or ABORTED; QP cannot retry access requests");
            case ABORTED -> new AccessDecision(AccessStatus.ABORTED, Optional.empty(), reqId);
        };
    }

    @Override
    public AccessDecision waitAccess(UUID requestId) {
        throw failure("CCM access is blocking; waitAccess is not supported by this adapter");
    }

    @Override
    public WritePermit authorizeChange(ChangeRecord change) {
        AccessResponse resp = awaitWriteGrant(change.txId(),
                () -> ccm.requestWrite(change.txId().value(), change.value()));
        long lsn = resp.changeLSN().isPresent()
                ? resp.changeLSN().get()
                : (frm != null
                        ? frm.logChange(change.txId().value(), change.value())
                        : fallbackLsn.incrementAndGet());
        WritePermit permit = new WritePermit(change.id(), new Lsn(lsn));
        permits.put(change.id(), permit);
        return permit;
    }

    @Override
    public WritePermit authorizeSchemaChange(SchemaChangeRecord change) {
        AccessResponse resp = awaitWriteGrant(change.txId(),
                () -> ccm.requestSchemaWrite(change.txId().value(), change.value()));
        long lsn = resp.changeLSN().isPresent()
                ? resp.changeLSN().get()
                : (frm != null
                        ? frm.logSchemaChange(change.txId().value(), change.value())
                        : fallbackLsn.incrementAndGet());
        WritePermit permit = new WritePermit(change.id(), new Lsn(lsn));
        permits.put(change.id(), permit);
        return permit;
    }

    private AccessResponse awaitWriteGrant(TxId txId, Supplier<AccessResponse> request) {
        getCancellationToken(txId).check();
        if (getState(txId) != TransactionState.ACTIVE) {
            throw failure("Transaction not active: " + txId.value());
        }
        AccessResponse response = checkedAccessResponse(txId, request.get());
        if (response.status() == AccessStatus.ABORTED) {
            throw new QpException(ErrorCode.TRANSACTION_ABORTED, "CCM aborted transaction " + txId.value());
        }
        getCancellationToken(txId).check();
        if (response.status() != AccessStatus.GRANTED) {
            throw failure("CCM must block until GRANTED or ABORTED; QP cannot retry writes");
        }
        return response;
    }

    private AccessResponse checkedAccessResponse(TxId txId, AccessResponse response) {
        if (response == null || response.status() == null) {
            throw failure("CCM returned no access status");
        }
        if (response.status() == AccessStatus.ABORTED
                || response.error().filter(error -> error.code() == ErrorCode.TRANSACTION_ABORTED).isPresent()) {
            markAborted(txId);
        }
        if (response.error().isPresent()) {
            throw new QpException(response.error().get());
        }
        return response;
    }

    @Override
    public WriteLease acquireApplyLease(WritePermit permit) {
        if (getState(permit.changeId().txId()) != TransactionState.ACTIVE) {
            throw failure("Transaction not active");
        }
        if (!permit.equals(permits.get(permit.changeId())) || !leases.add(permit.changeId())) {
            throw failure("Invalid apply lease");
        }
        long txId = permit.changeId().txId().value();
        long lsn = permit.lsn().value();
        if (!ccm.beginApply(txId, lsn)) {
            leases.remove(permit.changeId());
            throw failure("CCM rejected beginApply for txId=" + txId + " lsn=" + lsn);
        }
        return () -> {
            try {
                ccm.endApply(txId, lsn);
            } finally {
                leases.remove(permit.changeId());
            }
        };
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
        return states.getOrDefault(txId, TransactionState.FAILED);
    }

    @Override
    public CancellationToken getCancellationToken(TxId txId) {
        return tokens.computeIfAbsent(txId, k -> new CancellationToken());
    }

    @Override
    public TransactionState commit(TxId txId) {
        TransactionResponse response;
        try {
            response = ccm.commit(txId.value());
        } catch (RuntimeException failure) {
            // The commit record might be durable even if CCM could not confirm it.
            states.put(txId, TransactionState.PARTIALLY_COMMITTED);
            throw failure;
        }
        TransactionState state = response == null || response.status() == null
                ? TransactionState.PARTIALLY_COMMITTED
                : switch (response.status()) {
                    case COMMITTED -> TransactionState.COMMITTED;
                    case ABORTED -> TransactionState.ABORTED;
                    case PARTIALLY_COMMITTED -> TransactionState.PARTIALLY_COMMITTED;
                    default -> TransactionState.FAILED;
                };
        if (state == TransactionState.ABORTED) {
            markAborted(txId);
        } else {
            states.put(txId, state);
        }
        return state;
    }

    @Override
    public TransactionState abort(TxId txId, String reason) {
        TransactionResponse resp = ccm.abort(txId.value());
        TransactionState state = resp == null || resp.status() == null
                ? TransactionState.FAILED
                : TransactionState.valueOf(resp.status().name());
        if (state == TransactionState.ABORTED) {
            markAborted(txId);
        } else {
            states.put(txId, state);
            if (resp != null && resp.error().isPresent()) {
                throw new QpException(resp.error().get());
            }
        }
        return state;
    }

    private void markAborted(TxId txId) {
        states.put(txId, TransactionState.ABORTED);
        getCancellationToken(txId).cancel();
        permits.keySet().removeIf(id -> id.txId().equals(txId));
        leases.removeIf(id -> id.txId().equals(txId));
    }

    /** Register transactions started directly through CCM before requesting grants through this adapter. */
    public void registerExternalTransaction(long txId) {
        TxId id = new TxId(txId);
        states.putIfAbsent(id, TransactionState.ACTIVE);
        tokens.putIfAbsent(id, new CancellationToken());
    }
}
