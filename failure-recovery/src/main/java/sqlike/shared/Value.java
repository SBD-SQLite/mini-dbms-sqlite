package sqlike.shared;

import java.util.Optional;

/** One typed value; only the payload matching {@code type} is present. */
public record Value(
        DataType type,
        Optional<Integer> integerValue,
        Optional<Double> floatValue,
        Optional<String> textValue) {
}
