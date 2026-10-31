package sqlike.shared;

import java.util.List;

/** Record state before or after a change; {@code exists == false} means "no record", not SQL NULL. */
public record RecordImage(boolean exists, List<Value> values) {
}
