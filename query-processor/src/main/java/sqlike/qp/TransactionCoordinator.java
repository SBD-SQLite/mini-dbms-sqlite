package sqlike.qp;

import java.util.Objects;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.failure;

public final class TransactionCoordinator {
    private static final String COMMIT_OUTCOME_UNKNOWN = "Commit outcome unknown. Recovery required.";

    private final IConcurrencyControl concurrency;
    private final QpConfiguration configuration;

    public TransactionCoordinator(IConcurrencyControl concurrency, QpConfiguration configuration) {
        this.concurrency = Objects.requireNonNull(concurrency);
        this.configuration = Objects.requireNonNull(configuration);
    }

    public TxId beginExplicit(SessionState session) {
        refreshTransactionState(session);
        if (session.getCurrentTxId().isPresent()) {
            throw failure("Transaction already active");
        }
        TxId tx = concurrency.begin(session.sessionId());
        session.attachTransaction(tx.value(), true);
        return tx;
    }

    public ExecutionContext ensureTransaction(SessionState session) {
        refreshTransactionState(session);
        if (session.getCurrentTxId().isEmpty()) {
            session.attachTransaction(concurrency.begin(session.sessionId()).value(), false);
        }
        TxId tx = new TxId(session.getCurrentTxId().orElseThrow());
        return new ExecutionContext(session.sessionId(), tx, !session.isExplicitTransaction(), session.sequence, concurrency.getCancellationToken(tx));
    }

    public TransactionState commit(SessionState session) {
        TxId tx = new TxId(session.getCurrentTxId().orElseThrow(() -> failure("No transaction to commit")));
        if (concurrency.getState(tx) == TransactionState.PARTIALLY_COMMITTED) {
            return TransactionState.PARTIALLY_COMMITTED;
        }
        TransactionState state = concurrency.commit(tx);
        clearCompleted(session, state);
        return state;
    }

    public TransactionState abort(SessionState session, String reason) {
        TxId tx = new TxId(session.getCurrentTxId().orElseThrow(() -> failure("No transaction to abort")));
        if (concurrency.getState(tx) == TransactionState.PARTIALLY_COMMITTED) {
            return TransactionState.PARTIALLY_COMMITTED;
        }
        TransactionState state = concurrency.abort(tx, reason);
        clearCompleted(session, state);
        return state;
    }

    /**
     * Route QP1's public auto-commit here and failures to {@link #handleRuntimeFailure}.
     * This preserves PARTIALLY_COMMITTED for recovery.
     */
    public void finishImplicit(SessionState session, ExecutionContext context) {
        if (!session.getId().equals(context.sessionId.orElse(null)) || session.getCurrentTxId().filter(id -> id == context.txId).isEmpty()) {
            throw failure("Context does not match session transaction");
        }
        if (context.implicit) {
            TransactionState state = commit(session);
            if (state == TransactionState.PARTIALLY_COMMITTED) {
                throw failure(COMMIT_OUTCOME_UNKNOWN);
            }
            if (state != TransactionState.COMMITTED) {
                throw failure("Auto-commit rejected: " + state);
            }
        }
    }

    public void handleRuntimeFailure(SessionState session, ExecutionContext context, DbmsError error) {
        if (!session.getId().equals(context.sessionId.orElse(null))) {
            throw failure("Wrong session context");
        }
        if (session.getCurrentTxId().filter(id -> id == context.txId).isEmpty()) {
            return;
        }
        TransactionState state = concurrency.getState(new TxId(context.txId));
        if (state == TransactionState.PARTIALLY_COMMITTED) {
            // Recovery must resolve the commit outcome before any rollback.
            return;
        }
        if (state != TransactionState.COMMITTED && state != TransactionState.ABORTED) {
            state = concurrency.abort(new TxId(context.txId), error.message());
        }
        clearCompleted(session, state);
        if (session.getCurrentTxId().isPresent()) {
            throw failure("Rollback incomplete: " + state);
        }
    }

    public void closeSession(SessionState session) {
        if (session.getCurrentTxId().isEmpty()) {
            return;
        }
        TransactionState state = concurrency.getState(new TxId(session.getCurrentTxId().orElseThrow()));
        if (state != TransactionState.COMMITTED && state != TransactionState.ABORTED) {
            state = abort(session, "Session closed");
        }
        if (state == TransactionState.PARTIALLY_COMMITTED) {
            throw failure(COMMIT_OUTCOME_UNKNOWN);
        }
        clearCompleted(session, state);
        if (session.getCurrentTxId().isPresent()) {
            throw failure("Session rollback incomplete");
        }
    }

    private void refreshTransactionState(SessionState session) {
        if (session.getCurrentTxId().isEmpty()) {
            return;
        }
        TransactionState state = concurrency.getState(new TxId(session.getCurrentTxId().orElseThrow()));
        if (state == TransactionState.COMMITTED) {
            clearCompleted(session, state);
            return;
        }
        if (state == TransactionState.ABORTED) {
            clearCompleted(session, state);
            throw failure("Previous transaction was aborted; retry in a new transaction");
        }
        if (state == TransactionState.PARTIALLY_COMMITTED) {
            throw failure(COMMIT_OUTCOME_UNKNOWN);
        }
        if (state != TransactionState.ACTIVE) {
            throw failure("Transaction is " + state + "; ABORT required");
        }
    }

    private void clearCompleted(SessionState session, TransactionState state) {
        if (state == TransactionState.COMMITTED || state == TransactionState.ABORTED) {
            session.clearTransaction();
        }
    }
}
