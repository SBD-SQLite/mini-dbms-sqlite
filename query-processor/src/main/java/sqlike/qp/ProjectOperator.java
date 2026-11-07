package sqlike.qp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    private static List<ColumnRef> expandWildcards(Schema childSchema, ProjectSpec spec) {
        if (childSchema == null) {
            throw new IllegalArgumentException("Child schema cannot be null in ProjectOperator");
        }
        if (spec == null) {
            throw new IllegalArgumentException("ProjectSpec cannot be null in ProjectOperator");
        }
        if (spec.columns().isEmpty()) {
            throw new IllegalArgumentException("Projection columns cannot be empty in ProjectOperator");
        }
        List<ColumnRef> expanded = new ArrayList<>();
        for (ColumnRef colRef : spec.columns()) {
            if (colRef == null) {
                throw new IllegalArgumentException("ColumnRef in ProjectSpec cannot be null");
            }
            String colName = colRef.columnName() != null ? colRef.columnName().trim() : "";
            if ("*".equals(colName)) {
                if (colRef.tableName().isEmpty() || colRef.tableName().get().isBlank()) {
                    if (childSchema.columnCount() == 0) {
                        throw new IllegalArgumentException("Cannot expand '*' wildcard on empty child schema");
                    }
                    for (int i = 0; i < childSchema.columnCount(); i++) {
                        ColumnSchema col = childSchema.getColumn(i);
                        Optional<String> owner = childSchema.columnTablesAt(i).stream().findFirst()
                                .or(childSchema::tableName);
                        expanded.add(new ColumnRef(owner, col.name()));
                    }
                } else {
                    String prefix = colRef.tableName().get().trim();
                    String normPrefix = prefix.toLowerCase(Locale.ROOT);
                    int matchedCount = 0;
                    for (int i = 0; i < childSchema.columnCount(); i++) {
                        if (matchesTableOwner(childSchema, i, normPrefix)) {
                            expanded.add(new ColumnRef(Optional.of(prefix), childSchema.getColumn(i).name()));
                            matchedCount++;
                        }
                    }
                    if (matchedCount == 0) {
                        throw new IllegalArgumentException(
                                "Table or alias not found in schema for wildcard projection: " + prefix + ".*");
                    }
                }
            } else {
                expanded.add(colRef);
            }
        }
        return List.copyOf(expanded);
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
        List<ColumnRef> expanded = expandWildcards(childSchema, spec);
        List<Integer> resolvedOffsets = new ArrayList<>(expanded.size());
        for (ColumnRef colRef : expanded) {
            resolvedOffsets.add(childSchema.findColumnOffset(colRef));
        }
        return List.copyOf(resolvedOffsets);
    }

    private static Schema buildProjectedSchema(Operator child, ProjectSpec spec, Schema providedSchema) {
        if (child == null || spec == null || spec.columns().isEmpty()) {
            return providedSchema;
        }
        Schema childSchema = child.schema();
        if (childSchema == null) {
            return providedSchema;
        }
        List<ColumnRef> expanded;
        try {
            expanded = expandWildcards(childSchema, spec);
        } catch (RuntimeException e) {
            return providedSchema;
        }
        boolean useProvidedColumns = providedSchema != null && providedSchema.columnCount() == expanded.size();
        List<ColumnSchema> projectedCols = new ArrayList<>(expanded.size());
        List<Set<String>> owners = new ArrayList<>(expanded.size());
        for (int i = 0; i < expanded.size(); i++) {
            ColumnRef ref = expanded.get(i);
            if (ref == null) {
                return providedSchema;
            }
            int offset;
            try {
                offset = childSchema.findColumnOffset(ref);
            } catch (RuntimeException e) {
                return providedSchema;
            }
            ColumnSchema col = useProvidedColumns ? providedSchema.getColumn(i) : childSchema.getColumn(offset);
            projectedCols.add(col);
            Set<String> set = new LinkedHashSet<>(childSchema.columnTablesAt(offset));
            if (useProvidedColumns) {
                set.addAll(providedSchema.columnTablesAt(i));
            }
            ref.tableName().ifPresent(set::add);
            owners.add(set);
        }
        Optional<String> outTableName = providedSchema != null && providedSchema.tableName().isPresent()
                ? providedSchema.tableName()
                : childSchema.tableName();
        return Schema.withColumnTables(projectedCols, outTableName, owners);
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
