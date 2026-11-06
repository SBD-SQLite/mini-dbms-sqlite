package sqlike.shared;
import java.util.List;

public record Record(RID rid, List<Value> values) {
    public Record { values = values == null ? List.of() : List.copyOf(values); }
}
