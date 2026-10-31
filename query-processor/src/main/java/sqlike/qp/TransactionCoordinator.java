package sqlike.qp;

import java.util.Objects;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.StubRuntime.SessionState;
import static sqlike.qp.port.QpContracts.failure;

public final class TransactionCoordinator {
    private final IConcurrencyControl concurrency;
    private final QpConfiguration configuration;
    public TransactionCoordinator(IConcurrencyControl concurrency, QpConfiguration configuration) {
        this.concurrency = Objects.requireNonNull(concurrency); 
        this.configuration = Objects.requireNonNull(configuration);
    }
    public TxId beginExplicit(SessionState session) {
        refreshTransactionState(session);
        if (session.getCurrentTxId().isPresent()) throw failure("Transaction already active");
        TxId tx = concurrency.begin(session.id); 
        session.attachTransaction(tx, true); 
        return tx;
    }
    public ExecutionContext ensureTransaction(SessionState session) {
        refreshTransactionState(session);
        if (session.getCurrentTxId().isEmpty()) session.attachTransaction(concurrency.begin(session.id), false);
        TxId tx = session.getCurrentTxId().orElseThrow();
        return new ExecutionContext(session.id, tx, !session.isExplicitTransaction(), session.sequence, concurrency.getCancellationToken(tx));
    }
    public TransactionState commit(SessionState session) {
        TxId tx = session.getCurrentTxId().orElseThrow(() -> failure("No transaction to commit"));
        TransactionState state = concurrency.commit(tx); 
        clearCompleted(session, state); 
        return state;
    }
    public TransactionState abort(SessionState session, String reason) {
        TxId tx = session.getCurrentTxId().orElseThrow(() -> failure("No transaction to abort"));
        TransactionState state = concurrency.abort(tx, reason); 
        clearCompleted(session, state); 
        return state;
    }
    public void finishImplicit(SessionState session, ExecutionContext context) {
        if (!session.id.equals(context.sessionId) || session.getCurrentTxId().filter(context.txId::equals).isEmpty())
            throw failure("Context does not match session transaction");
        if (context.implicit) {
            TransactionState state = commit(session);
            if (state != TransactionState.COMMITTED) throw failure("Auto-commit rejected: " + state);
        }
    }
    public void handleRuntimeFailure(SessionState session, ExecutionContext context, DbmsError error) {
        if (!session.id.equals(context.sessionId)) throw failure("Wrong session context");
        if (session.getCurrentTxId().filter(context.txId::equals).isEmpty()) return;
        TransactionState state = concurrency.getState(context.txId);
        if (state != TransactionState.COMMITTED && state != TransactionState.ABORTED) state = concurrency.abort(context.txId, error.message());
        clearCompleted(session, state);
        if (session.getCurrentTxId().isPresent()) throw failure("Rollback incomplete: " + state);
    }
    public void closeSession(SessionState session) {
        if (session.getCurrentTxId().isEmpty()) return;
        TransactionState state = concurrency.getState(session.getCurrentTxId().orElseThrow());
        if (state != TransactionState.COMMITTED && state != TransactionState.ABORTED) state = abort(session, "Session closed");
        clearCompleted(session, state);
        if (session.getCurrentTxId().isPresent()) throw failure("Session rollback incomplete");
    }
    private void refreshTransactionState(SessionState session) {
        if (session.getCurrentTxId().isEmpty()) return;
        TransactionState state = concurrency.getState(session.getCurrentTxId().orElseThrow());
        if (state == TransactionState.COMMITTED) { clearCompleted(session, state); return; }
        if (state == TransactionState.ABORTED) {
            clearCompleted(session, state); 
            throw failure("Previous transaction was aborted; retry in a new transaction");
        }
        if (state != TransactionState.ACTIVE) throw failure("Transaction is " + state + "; ABORT required");
    }
    private void clearCompleted(SessionState session, TransactionState state) {
        if (state == TransactionState.COMMITTED || state == TransactionState.ABORTED) session.clearTransaction();
    }
}
