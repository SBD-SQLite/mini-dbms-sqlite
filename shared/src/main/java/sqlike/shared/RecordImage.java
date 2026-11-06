package sqlike.shared;

import java.util.List;

public record RecordImage(
        boolean exists,
        List<Value> values
) {
}
