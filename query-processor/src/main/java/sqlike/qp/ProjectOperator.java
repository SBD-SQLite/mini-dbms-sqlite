package sqlike.qp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import sqlike.shared.ColumnRef;
import sqlike.shared.Value;

public final class ProjectOperator extends AbstractUnaryOperator {
    private final List<Integer> offsets;

    public ProjectOperator(Operator child, ProjectSpec spec, Schema schema) {
        super(child, enrichSchema(child, spec, schema));
        if (spec == null) {
            throw new IllegalArgumentException("ProjectSpec cannot be null in ProjectOperator");
        }
        if (spec.columns().isEmpty()) {
            throw new IllegalArgumentException("Projection columns cannot be empty in ProjectOperator");
        }
        List<Integer> resolvedOffsets = new ArrayList<>(spec.columns().size());
        Schema childSchema = child.schema();
        for (ColumnRef colRef : spec.columns()) {
            if (colRef == null) {
                throw new IllegalArgumentException("ColumnRef in ProjectSpec cannot be null");
            }
            resolvedOffsets.add(childSchema.findColumnOffset(colRef));
        }
        this.offsets = List.copyOf(resolvedOffsets);
    }

    private static Schema enrichSchema(Operator child, ProjectSpec spec, Schema schema) {
        if (child == null || spec == null || schema == null || spec.columns().size() != schema.columnCount()) {
            return schema;
        }
        Schema childSchema = child.schema();
        if (childSchema == null) {
            return schema;
        }
        List<Set<String>> owners = new ArrayList<>(spec.columns().size());
        for (int i = 0; i < spec.columns().size(); i++) {
            ColumnRef ref = spec.columns().get(i);
            if (ref == null) {
                return schema;
            }
            int offset = childSchema.findColumnOffset(ref);
            Set<String> set = new LinkedHashSet<>(childSchema.columnTablesAt(offset));
            set.addAll(schema.columnTablesAt(i));
            ref.tableName().ifPresent(set::add);
            owners.add(set);
        }
        return Schema.withColumnTables(schema.columns(), schema.tableName(), owners);
    }

    @Override
    protected void doOpen() {
        child.open(requireContext());
    }

    @Override
    protected Optional<RowEnvelope> doNext() {
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
}
