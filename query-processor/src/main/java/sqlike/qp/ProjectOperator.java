package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import sqlike.shared.ColumnRef;
import sqlike.shared.ColumnSchema;
import sqlike.shared.ErrorCode;
import sqlike.shared.Value;

/**
 * Unary row operator projecting and reordering columns, supporting direct wildcard (*)
 * and table/alias-prefixed wildcard (t.*) expansion while preserving RID identity (QP3 — Milestone 2).
 */
public final class ProjectOperator extends AbstractUnaryOperator {
    private final List<Integer> offsets;

    public ProjectOperator(Operator child, ProjectSpec spec, Schema schema) {
        super(child, buildProjectedSchema(child, spec, schema));
        if (spec == null) {
            throw new IllegalArgumentException("ProjectSpec cannot be null in ProjectOperator");
        }
        if (spec.columns().isEmpty()) {
            throw new IllegalArgumentException("Projection columns cannot be empty in ProjectOperator");
        }
        this.offsets = resolveOffsets(child.schema(), spec);
    }

    public ProjectOperator(Operator child, ProjectSpec spec) {
        this(child, spec, child != null ? Schema.of(child.schema().tableName().orElse(null), List.of()) : null);
    }

    public List<Integer> getOffsets() {
        return offsets;
    }

    public Operator getChild() {
        return child;
    }

    private static boolean matchesTableOwner(Schema schema, int index, String normPrefix) {
        if (schema.columnTablesAt(index).contains(normPrefix)) {
            return true;
        }
        if (schema.tableName().isPresent()
                && schema.tableName().get().trim().equalsIgnoreCase(normPrefix)) {
            return true;
        }
        String colName = schema.getColumn(index).name();
        if (colName != null) {
            int dot = colName.indexOf('.');
            if (dot > 0 && colName.substring(0, dot).trim().equalsIgnoreCase(normPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> resolveOffsets(Schema childSchema, ProjectSpec spec) {
        if (childSchema == null || spec == null || spec.columns().isEmpty()) {
            throw new IllegalArgumentException("Projection requires a child schema and columns");
        }
        List<Integer> resolved = new ArrayList<>();
        for (ColumnRef ref : spec.columns()) {
            if (!"*".equals(ref.columnName().trim())) {
                resolved.add(childSchema.findColumnOffset(ref));
                continue;
            }
            int before = resolved.size();
            String owner = ref.tableName().filter(t -> !t.isBlank())
                    .map(t -> t.trim().toLowerCase(Locale.ROOT)).orElse(null);
            for (int i = 0; i < childSchema.columnCount(); i++) {
                if (owner == null || matchesTableOwner(childSchema, i, owner)) resolved.add(i);
            }
            if (resolved.size() == before) throw new IllegalArgumentException("Wildcard has no matching columns: " + ref);
        }
        return List.copyOf(resolved);
    }

    private static Schema buildProjectedSchema(Operator child, ProjectSpec spec, Schema providedSchema) {
        if (child == null) throw new IllegalArgumentException("Projection child cannot be null");
        Schema source = child.schema();
        List<Integer> offsets = resolveOffsets(source, spec);
        boolean useProvided = providedSchema != null && providedSchema.columnCount() == offsets.size();
        List<ColumnSchema> columns = new ArrayList<>(offsets.size());
        List<Set<String>> owners = new ArrayList<>(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            int offset = offsets.get(i);
            ColumnSchema column = useProvided ? providedSchema.getColumn(i) : source.getColumn(offset);
            if (column.dataType() != source.getColumn(offset).dataType()) {
                throw new IllegalArgumentException("Projection schema type differs from source at slot " + i);
            }
            columns.add(column);
            owners.add(source.columnTablesAt(offset));
        }
        Optional<String> table = providedSchema != null && providedSchema.tableName().isPresent()
                ? providedSchema.tableName() : source.tableName();
        return Schema.withColumnTables(columns, table, owners);
    }

    @Override
    protected void doOpen() {
        ExecutionContext ctx = requireContext();
        checkContextActive(ctx);
        child.open(ctx);
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
        checkContextActive(requireContext());
        Optional<RowEnvelope> nextInput = child.next();
        if (nextInput.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(projectRow(nextInput.get()));
    }

    @Override
    protected void doClose() {
        child.close();
    }

    private RowEnvelope projectRow(RowEnvelope input) {
        if (input == null) {
            throw new IllegalArgumentException("Input RowEnvelope cannot be null in projectRow");
        }
        List<Value> projectedValues = new ArrayList<>(offsets.size());
        Row inputRow = input.row();
        for (int offset : offsets) {
            projectedValues.add(inputRow.getValue(offset));
        }
        Row projectedRow = Row.of(projectedValues);
        return input.rid()
                .map(rid -> RowEnvelope.of(projectedRow, rid))
                .orElseGet(() -> RowEnvelope.of(projectedRow));
    }

    private void checkContextActive(ExecutionContext ctx) {
        try {
            ctx.check();
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Transaction cancelled in ProjectOperator";
            throw new QpException(ErrorCode.TRANSACTION_ABORTED, msg);
        }
    }
}
