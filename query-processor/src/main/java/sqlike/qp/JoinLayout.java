package sqlike.qp;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;

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
        inputSchema = Schema.of(columns);
        outputSchema = Objects.requireNonNull(output);
        outputMapping = List.copyOf(mapping);
        validateMapping();
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

    // shortcut: provenance kosong pada M1, lengkapi setelah RowEnvelope multi-relasi disepakati.
    private Map<String,RecordRef> mergeOrigins(RowEnvelope left, RowEnvelope right) {return Map.of();}

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

class RecordRef {}
