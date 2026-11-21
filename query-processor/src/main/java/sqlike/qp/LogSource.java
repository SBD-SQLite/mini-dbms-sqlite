package sqlike.qp;

import java.util.Optional;

@FunctionalInterface
public interface LogSource {

    String showLogs(Optional<Long> txId);
}
