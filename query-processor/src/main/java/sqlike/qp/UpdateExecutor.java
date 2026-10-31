package sqlike.qp;

import java.util.*;
import sqlike.qp.port.QpContracts.*;
import sqlike.shared.ColumnRef;
import sqlike.shared.RID;
import static sqlike.qp.port.QpContracts.failure;

public final class UpdateExecutor extends AbstractCommandExecutor {
    private final Operator source;
    private final UpdateSpec spec;
    private final Schema tableSchema;
    private final MutationTargetCollector collector;
    private final IExpressionEvaluator evaluator;
    private final MutationCoordinator mutations;

    public UpdateExecutor(
            Operator source,
            UpdateSpec spec,
            Schema tableSchema,
            MutationTargetCollector collector,
            IExpressionEvaluator evaluator,
            MutationCoordinator mutations) {
        super();
        this.source = Objects.requireNonNull(source);
        this.spec = Objects.requireNonNull(spec);
        this.tableSchema = Objects.requireNonNull(tableSchema);
        this.collector = Objects.requireNonNull(collector);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.mutations = Objects.requireNonNull(mutations);
    }

    public Operator getSource() {
        return source;
    }

    public UpdateSpec getSpec() {
        return spec;
    }

    @Override
    protected long doExecute(ExecutionContext context) {
        context.requireGrant(spec.tableName(), AccessMode.WRITE);
        var targets = new LinkedHashMap<RID, StoredRecord>();
        for (StoredRecord record : collector.collect(source, spec.tableName(), context)) {
            targets.putIfAbsent(record.rid(), record);
        }
        var values = new ArrayList<Row>();
        for (StoredRecord before : targets.values()) {
            context.check();
            values.add(calculateAfter(before));
        }
        long count = 0;
        int index = 0;
        for (StoredRecord before : targets.values()) {
            if (mutations.update(spec.tableName(), before, values.get(index++), context)) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected void doClose() {
        source.close();
    }

    private Row calculateAfter(StoredRecord before) {
        ColumnRef colRef = spec.column();
        int column = tableSchema.offset(colRef);
        var res = evaluator.evaluate(spec.value().value(), before.row(), tableSchema);
        var value = res.value();
        if (value == null) {
            throw failure("SET requires a value");
        }
        var copy = new ArrayList<>(before.row().values());
        copy.set(column, tableSchema.coerce(column, value));
        return new Row(copy);
    }
}
