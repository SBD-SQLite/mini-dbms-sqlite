package sqlike.shared;

import java.util.OptionalInt;
import java.util.OptionalLong;

public record IndexStatistics(
        OptionalInt height,
        OptionalInt bucketCount,
        OptionalLong leafPageCount,
        OptionalLong overflowPageCount,
        boolean clustered
) {
}
