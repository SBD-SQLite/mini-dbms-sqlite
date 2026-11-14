package sqlike.qp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import sqlike.shared.ColumnSchema;

public class JoinLayout {
    private Schema leftSchema;
    private Schema rightSchema;
    private Schema inputSchema;
    private Schema outputSchema;
    private List<Integer> outputMapping;

    public JoinLayout(Schema left, Schema right, Schema output, List<Integer> mapping) {
        leftSchema = Objects.requireNonNull(left);
        rightSchema = Objects.requireNonNull(right);
        var columns = new ArrayList<>(left.columns());
        columns.addAll(right.columns());
        List<Set<String>> inputOwners = new ArrayList<>(columns.size());
        for (int i = 0; i < left.columnCount(); i++) {
            inputOwners.add(left.columnTablesAt(i));
        }
        for (int i = 0; i < right.columnCount(); i++) {
            inputOwners.add(right.columnTablesAt(i));
        }
        inputSchema = Schema.withColumnTables(columns, java.util.Optional.empty(), inputOwners);

        Objects.requireNonNull(output);
        outputMapping = List.copyOf(Objects.requireNonNull(mapping));
        outputSchema = output;
        validateMapping();

        List<Set<String>> outputOwners = new ArrayList<>(outputMapping.size());
        for (int i = 0; i < outputMapping.size(); i++) {
            int srcOffset = outputMapping.get(i);
            Set<String> owners = new LinkedHashSet<>(inputSchema.columnTablesAt(srcOffset));
            owners.addAll(output.columnTablesAt(i));
            ColumnSchema outCol = output.getColumn(i);
            if (srcOffset < left.columnCount()) {
                for (int r = 0; r < right.columnCount(); r++) {
                    int rightInputOffset = left.columnCount() + r;
                    if (!outputMapping.contains(rightInputOffset)
                            && right.getColumn(r).name().equalsIgnoreCase(outCol.name())) {
                        owners.addAll(right.columnTablesAt(r));
                    }
                }
            }
            outputOwners.add(owners);
        }
        outputSchema = Schema.withColumnTables(output.columns(), output.tableName(), outputOwners);
    }

    public Schema getInputSchema() {
        return inputSchema;
    }

    public Schema getOutputSchema() {
        return outputSchema;
    }

    public List<Integer> getOutputMapping() {
        return outputMapping;
    }

    public Row combineForPredicate(RowEnvelope left, RowEnvelope right) {
        if (left.row().size() != leftSchema.columnCount() || right.row().size() != rightSchema.columnCount()) {
            throw new IllegalArgumentException("Join rows must match their input schemas");
        }
        var values = new ArrayList<>(left.row().values());
        values.addAll(right.row().values());
        return Row.of(values);
    }

    public RowEnvelope combineForOutput(RowEnvelope left, RowEnvelope right) {
        Row input = combineForPredicate(left, right);
        return RowEnvelope.of(Row.of(outputMapping.stream().map(input::getValue).toList()));
    }

    private Map<String, RecordRef> mergeOrigins(RowEnvelope left, RowEnvelope right) {
        Map<String, RecordRef> origins = new LinkedHashMap<>();
        if (left != null && left.rid().isPresent() && leftSchema.tableName().isPresent()) {
            origins.put(leftSchema.tableName().get(), new RecordRef(leftSchema.tableName().get(), left.rid().get()));
        }
        if (right != null && right.rid().isPresent() && rightSchema.tableName().isPresent()) {
            origins.put(rightSchema.tableName().get(), new RecordRef(rightSchema.tableName().get(), right.rid().get()));
        }
        return Map.copyOf(origins);
    }

    private void validateMapping() {
        if (outputMapping.size() != outputSchema.columnCount()) {
            throw new IllegalArgumentException("Join mapping width must match output schema");
        }
        for (int i = 0; i < outputMapping.size(); i++) {
            int offset = outputMapping.get(i);
            if (offset < 0 || offset >= inputSchema.columnCount()
                    || inputSchema.getColumn(offset).dataType() != outputSchema.getColumn(i).dataType()) {
                throw new IllegalArgumentException("Invalid join output mapping at slot " + i);
            }
        }
    }
}

record RecordRef(String tableName, sqlike.shared.RID rid) {
    RecordRef() {
        this("", null);
    }
}
