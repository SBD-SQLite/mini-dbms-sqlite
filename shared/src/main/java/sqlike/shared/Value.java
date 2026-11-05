package sqlike.shared;

import java.util.Optional;

public record Value(
        DataType type,
        Optional<Integer> integerValue,
        Optional<Double> floatValue,
        Optional<String> textValue
) {
}
