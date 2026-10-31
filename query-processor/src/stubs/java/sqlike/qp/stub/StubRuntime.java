package sqlike.qp.stub;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import sqlike.shared.*;
import sqlike.qp.port.QpContracts.*;
import static sqlike.qp.port.QpContracts.*;

/** STUB ONLY: missing QP1 session, QP2 bases/evaluator, and QP3 target collector. */
public final class StubRuntime {
    private StubRuntime() { }
    public static final class SessionState {
        public final SessionId id = new SessionId(UUID.randomUUID());
        public final AtomicLong sequence = new AtomicLong();
        private TxId tx;
        private boolean explicit;
        public Optional<TxId> getCurrentTxId() { return Optional.ofNullable(tx); }
        public boolean isExplicitTransaction() { return explicit; }
        public void attachTransaction(TxId id, boolean explicit) { tx = id; this.explicit = explicit; }
        public void clearTransaction() { tx = null; explicit = false; }
    }
    public abstract static class AbstractCommandExecutor implements AutoCloseable {
        private boolean executed, closed;
        public final long execute(ExecutionContext context) {
            if (closed || executed) throw failure("Command already executed or closed");
            executed = true; context.check(); return doExecute(context);
        }
        @Override public final void close() { if (!closed) { closed = true; doClose(); } }
        protected abstract long doExecute(ExecutionContext context);
        protected abstract void doClose();
    }
    public abstract static class AbstractOperator implements Operator {
        private final Schema schema;
        protected ExecutionContext context;
        protected AbstractOperator(Schema schema) { this.schema = schema; }
        @Override public final Schema schema() { return schema; }
        @Override public final void open(ExecutionContext context) {
            close(); this.context = context;
            try { context.check(); doOpen(); }
            catch (RuntimeException failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        protected ExecutionContext requireContext() {
            if (context == null) throw failure("Operator is not open"); return context;
        }
        @Override public final Optional<RowEnvelope> next() { requireContext().check(); return doNext(); }
        @Override public final void close() {
            if (context != null) { try { doClose(); } finally { context = null; } }
        }
        protected abstract void doOpen();
        protected abstract Optional<RowEnvelope> doNext();
        protected abstract void doClose();
    }
    public static class MutationTargetCollector {
        private final IStorageManager storage;
        public MutationTargetCollector(IStorageManager storage) { this.storage = storage; }
        public List<StoredRecord> collect(Operator source, TableId table, ExecutionContext context) {
            var targets = new LinkedHashMap<Rid, StoredRecord>();
            try (source) {
                source.open(context); Optional<RowEnvelope> next;
                while ((next = source.next()).isPresent()) {
                    RowEnvelope row = next.get();
                    if (!table.equals(row.tableId()) || row.rid() == null) throw failure("Mutation source lost its table/RID");
                    targets.putIfAbsent(row.rid(), storage.fetch(table, row.rid(), context, context.requireGrant(table, AccessMode.READ))
                            .orElseThrow(() -> failure("Mutation target no longer exists")));
                }
            }
            return List.copyOf(targets.values());
        }
    }

    /** STUB ONLY: a functional evaluator over the real QO expression tree. */
    public static final class StubExpressionEvaluator implements IExpressionEvaluator {
        @Override public ExpressionResult evaluate(Expr expr, Row row, Schema schema) {
            Expression tree = expr.value();
            if (tree.kind() == ExpressionKind.LITERAL) return value(tree.literal().orElseThrow());
            if (tree.kind() == ExpressionKind.COLUMN) return value(row.values().get(schema.offset(tree.column().orElseThrow())));
            ExpressionOperator op = tree.operator().orElseThrow();
            ExpressionResult left = evaluate(new Expr(tree.operands().get(0)), row, schema);
            if (op == ExpressionOperator.AND && Boolean.FALSE.equals(left.condition())) return condition(false);
            if (op == ExpressionOperator.NEGATE) {
                Object n = payload(left.value());
                return value(n instanceof Integer i ? sqlike.qo.Literals.integer(Math.negateExact(i)) : number(-((Number) n).doubleValue()));
            }
            ExpressionResult right = evaluate(new Expr(tree.operands().get(1)), row, schema);
            if (op == ExpressionOperator.AND) {
                if (left.condition() == null || right.condition() == null) throw failure("AND requires conditions");
                return condition(left.condition() && right.condition());
            }
            Object a = payload(left.value()), b = payload(right.value());
            if (Set.of(ExpressionOperator.ADD, ExpressionOperator.SUBTRACT, ExpressionOperator.MULTIPLY, ExpressionOperator.DIVIDE).contains(op)) {
                if (!(a instanceof Number x) || !(b instanceof Number y)) throw failure("Arithmetic requires numbers");
                if (op == ExpressionOperator.DIVIDE && y.doubleValue() == 0) throw failure("Division by zero");
                if (a instanceof Integer i && b instanceof Integer j) {
                    int n = switch (op) {
                        case ADD -> Math.addExact(i, j); case SUBTRACT -> Math.subtractExact(i, j);
                        case MULTIPLY -> Math.multiplyExact(i, j);
                        default -> { if (i == Integer.MIN_VALUE && j == -1) throw failure("Integer overflow"); yield i / j; }
                    };
                    return value(sqlike.qo.Literals.integer(n));
                }
                double n = switch (op) {
                    case ADD -> x.doubleValue() + y.doubleValue(); case SUBTRACT -> x.doubleValue() - y.doubleValue();
                    case MULTIPLY -> x.doubleValue() * y.doubleValue(); default -> x.doubleValue() / y.doubleValue();
                };
                return value(number(n));
            }
            int comparison;
            if (a instanceof Number x && b instanceof Number y) comparison = Double.compare(x.doubleValue(), y.doubleValue());
            else if (a instanceof String x && b instanceof String y) comparison = x.compareTo(y);
            else throw failure("Incompatible comparison types");
            return condition(switch (op) {
                case EQ -> comparison == 0; case NE -> comparison != 0; case GT -> comparison > 0;
                case GE -> comparison >= 0; case LT -> comparison < 0; case LE -> comparison <= 0;
                default -> throw failure("Unsupported comparison: " + op);
            });
        }
        private ExpressionResult value(Value value) { payload(value); return new ExpressionResult(value, null); }
        private ExpressionResult condition(boolean value) { return new ExpressionResult(null, value); }
        @Override public boolean evaluateCondition(Expr expr, Row row, Schema schema) {
            Boolean condition = evaluate(expr, row, schema).condition();
            if (condition == null) throw failure("WHERE requires a condition"); return condition;
        }
    }
}
