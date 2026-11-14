package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.port.StorageAdapter;
import sqlike.shared.SchemaChange;
import sqlike.shared.SchemaImage;
import static sqlike.qp.port.QpContracts.failure;

/** CREATE INDEX is an auto-commit schema mutation with WAL and apply protection. */
public final class CreateIndexExecutor extends AbstractCommandExecutor {
    private final CreateIndexSpec spec;
    private final MutationCoordinator mutations;
    private final IStorageManager storage;

    public CreateIndexExecutor(CreateIndexSpec spec, sqlike.contract.IStorageManager storage) {
        this(spec, new MutationCoordinator(new StorageAdapter(storage), null));
    }

    public CreateIndexExecutor(CreateIndexSpec spec, IStorageManager storage) {
        this(spec, new MutationCoordinator(storage, null));
    }

    public CreateIndexExecutor(CreateIndexSpec spec, MutationCoordinator mutations) {
        this.spec = Objects.requireNonNull(spec);
        this.mutations = Objects.requireNonNull(mutations);
        this.storage = Objects.requireNonNull(mutations.getStorage());
    }

    public CreateIndexSpec getSpec() {
        return spec;
    }

    @Override
    protected long doExecute(ExecutionContext context) {
        if (!context.implicit()) throw failure("CREATE INDEX is not allowed within an explicit transaction");
        mutations.requireWalContext(context);
        context.requireGrant("__catalog__", AccessMode.WRITE);
        var grant = context.requireGrant(spec.tableId(), AccessMode.WRITE);
        var schema = Objects.requireNonNull(storage.getTableSchema(spec.tableId()), "Missing table schema");
        Schema.fromTableSchema(schema).findColumnOffset(null, spec.definition().columnName());
        var indexes = List.copyOf(storage.getIndexes(spec.tableId()));
        if (indexes.stream().anyMatch(index -> index.indexName().equalsIgnoreCase(spec.definition().indexName()))) {
            throw failure("Index already exists: " + spec.definition().indexName());
        }
        var after = new ArrayList<>(indexes);
        after.add(spec.definition());
        var change = new SchemaChange(
                List.of(new SchemaImage(schema.tableName(), Optional.of(schema), indexes, Optional.empty())),
                List.of(new SchemaImage(schema.tableName(), Optional.of(schema), after, Optional.empty())));
        mutations.applySchemaChange(change, context, lsn -> storage.createIndex(spec, context, grant, lsn));
        return 0;
    }

    @Override
    protected void doClose() { }
}
