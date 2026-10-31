package sqlike.qp;

import java.util.*;
import sqlike.qp.port.QpContracts.*;
import sqlike.qp.stub.StubRuntime.AbstractOperator;
import static sqlike.qp.port.QpContracts.failure;

public final class IndexScanOperator extends AbstractOperator {
    private final IStorageManager storage;
    private final IndexScanSpec spec;
    private Optional<RidCursor> cursor = Optional.empty();
    public IndexScanOperator(IStorageManager storage, IndexScanSpec spec, Schema schema) {
        super(schema); this.storage = Objects.requireNonNull(storage); 
        this.spec = Objects.requireNonNull(spec);
    }
    @Override protected void doOpen() {
        var context = requireContext();
        cursor = Optional.of(storage.findIndex(spec.tableId(), spec.value().indexName().orElseThrow(),
                new IndexSearch(spec.value().condition().orElseThrow()), context, context.requireGrant(spec.tableId(), AccessMode.READ)));
    }
    @Override protected Optional<RowEnvelope> doNext() {
        Optional<Rid> rid = cursor.orElseThrow().next();
        if (rid.isEmpty()) return Optional.empty();
        StoredRecord record = fetchRecord(rid.get()); 
        return Optional.of(new RowEnvelope(record.tableId(), record.rid(), record.row()));
    }
    @Override protected void doClose() {
        Optional<RidCursor> previous = cursor; 
        cursor = Optional.empty(); 
        previous.ifPresent(RidCursor::close);
    }
    private StoredRecord fetchRecord(Rid rid) {
        var context = requireContext();
        return storage.fetch(spec.tableId(), rid, context, context.requireGrant(spec.tableId(), AccessMode.READ))
                .orElseThrow(() -> failure("Index points to a missing record"));
    }
}
