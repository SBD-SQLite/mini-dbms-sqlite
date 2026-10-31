package join;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class JoinLayout {
    private Schema inputSchema;
    private Schema outputSchema;
    private List<Integer> outputMapping;
    
    public JoinLayout(Schema left, Schema right, Schema output, List<Integer> mapping) {
        inputSchema = left;
        outputSchema = output;
        outputMapping = mapping;
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

    public Row combineForPredicate(RowEnvelope left, RowEnvelope right) {return new Row();}

    public RowEnvelope combineForOutput(RowEnvelope left, RowEnvelope right) {return new RowEnvelope();}

    private Map<String,RecordRef> mergeOrigins(RowEnvelope left, RowEnvelope right) {return new HashMap<String,RecordRef>();}

    private void validateMapping(){}

}

class Schema {}
class Row {
    public Row(){}
}
class RowEnvelope{
    public RowEnvelope(){}
}
class RecordRef {}