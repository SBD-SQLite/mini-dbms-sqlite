package sqlike.qp;

import java.util.*;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.StubRuntime.*;
import static sqlike.qp.port.QpContracts.failure;

public final class UpdateExecutor extends AbstractCommandExecutor {
    private final Operator source;
    private final UpdateSpec spec;
    private final Schema tableSchema;
    private final MutationTargetCollector collector;
    private final IExpressionEvaluator evaluator;
    private final MutationCoordinator mutations;
    public UpdateExecutor(Operator source, UpdateSpec spec, Schema tableSchema, MutationTargetCollector collector,
                          IExpressionEvaluator evaluator, MutationCoordinator mutations) {
        this.source = Objects.requireNonNull(source); 
        this.spec = Objects.requireNonNull(spec);
        this.tableSchema = Objects.requireNonNull(tableSchema); 
        this.collector = Objects.requireNonNull(collector);
        this.evaluator = Objects.requireNonNull(evaluator); 
        this.mutations = Objects.requireNonNull(mutations);
    }
    @Override protected long doExecute(ExecutionContext context) {
        context.requireGrant(spec.tableId(), AccessMode.WRITE);
        var targets = new LinkedHashMap<Rid, StoredRecord>();
        for (StoredRecord record : collector.collect(source, spec.tableId(), context)) {
            if (!record.tableId().equals(spec.tableId())) throw failure("Wrong update target table");
            targets.putIfAbsent(record.rid(), record);
        }
        var values = new ArrayList<Row>();
        for (StoredRecord before : targets.values()) { context.check(); values.add(calculateAfter(before)); }
        long count = 0; int index = 0;
        for (StoredRecord before : targets.values()) {
            if (mutations.update(spec.tableId(), before, values.get(index++), context)) count++;
        }
        return count;
    }
    @Override protected void doClose() { source.close(); }
    private Row calculateAfter(StoredRecord before) {
        int column = tableSchema.offset(spec.column());
        var value = evaluator.evaluate(spec.value(), before.row(), tableSchema).value();
        if (value == null) throw failure("SET requires a value");
        var copy = new ArrayList<>(before.row().values()); 
        copy.set(column, tableSchema.coerce(column, value)); 
        return new Row(copy);
    }
}
