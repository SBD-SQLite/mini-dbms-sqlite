package sqlike.qp;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


record LogCommand(Optional<Long> txId, boolean current) {
    private static final Pattern SHOW_LOG = Pattern.compile("(?is)^\s*SHOW\s+LOG(?:\s+(.*?))?\s*;?\s*$");

    static final String USAGE = "Usage: SHOW LOG [<txId> | CURRENT];";

    /** Empty when the statement is not a SHOW LOG command at all; throws when its argument is invalid. */
    static Optional<LogCommand> parse(String sql) {
        if (sql == null) {
            return Optional.empty();
        }
        Matcher m = SHOW_LOG.matcher(sql);
        if (!m.matches()) {
            return Optional.empty();
        }
        String arg = m.group(1);
        if (arg == null || arg.isBlank()) {
            return Optional.of(new LogCommand(Optional.empty(), false));
        }
        arg = arg.trim();
        if (arg.equalsIgnoreCase("CURRENT")) {
            return Optional.of(new LogCommand(Optional.empty(), true));
        }
        try {
            long tx = Long.parseLong(arg);
            if (tx > 0) {
                return Optional.of(new LogCommand(Optional.of(tx), false));
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new QpException(sqlike.shared.ErrorCode.SYNTAX_ERROR, "Invalid transaction id '" + arg + "'. " + USAGE);
    }
}
