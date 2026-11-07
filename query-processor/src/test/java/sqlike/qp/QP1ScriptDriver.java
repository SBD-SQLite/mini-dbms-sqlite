package sqlike.qp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import sqlike.qp.stub.StubQpRuntime;
import sqlike.shared.ErrorInfo;
import sqlike.shared.QueryResult;
import sqlike.shared.ResultColumn;
import sqlike.shared.ResultKind;
import sqlike.shared.Session;
import sqlike.shared.Value;

/**
 * QP-14 driver: runs SQL scripts through IQueryProcessor without the CLI and reports PASS/FAIL per scenario.
 * Usage: java sqlike.qp.QP1ScriptDriver [--verbose] [script.sql ...]
 * (default query-processor/test-scripts/qp14-acceptance.sql, fresh sample database per script).
 *
 * <pre>
 * -- scenario: NAME
 * -- session: NAME             default A
 * -- close-session: NAME       aborts the session's open transaction
 * -- expect: ok | rows N | columns a, b | affected N | transaction S | error [COMP] CODE | message TEXT
 * -- expect-row: v1 | v2       rows in order
 * </pre>
 * Expectations apply to the statement right before them; a statement without one must not fail.
 */
public final class QP1ScriptDriver {
    private static final Path DEFAULT_SCRIPT = Path.of("query-processor", "test-scripts", "qp14-acceptance.sql");
    private static final Set<String> EXPECTATION_KINDS =
            Set.of("ok", "rows", "columns", "affected", "transaction", "error", "message");

    private final QueryProcessor processor;
    private final boolean verbose;
    private int checks;

    private QP1ScriptDriver(QueryProcessor processor, boolean verbose) {
        this.processor = processor;
        this.verbose = verbose;
    }

    public static void main(String[] args) throws IOException {
        boolean verbose = false;
        List<Path> scripts = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--verbose")) {
                verbose = true;
            } else {
                scripts.add(Path.of(arg));
            }
        }
        if (scripts.isEmpty()) {
            scripts.add(locateDefaultScript());
        }

        int failedScenarios = 0;
        int totalScenarios = 0;
        int totalChecks = 0;
        for (Path script : scripts) {
            List<Scenario> scenarios;
            try {
                scenarios = parse(script);
            } catch (ScriptException e) {
                System.out.println("SCRIPT ERROR " + script + ": " + e.getMessage());
                System.exit(1);
                return;
            }
            System.out.println("QP-14 script " + script + " (" + scenarios.size() + " scenarios)");
            Path dataDir = Files.createTempDirectory("qp14-");
            try {
                StubQpRuntime runtime = StubQpRuntime.open(dataDir, QpConfiguration.defaults());
                runtime.recover();
                QP1ScriptDriver driver = new QP1ScriptDriver(runtime.processor, verbose);
                for (Scenario scenario : scenarios) {
                    totalScenarios++;
                    if (!driver.run(scenario)) {
                        failedScenarios++;
                    }
                }
                totalChecks += driver.checks;
                runtime.processor.shutdown();
            } finally {
                deleteRecursively(dataDir);
            }
        }
        System.out.println("RESULT: " + (totalScenarios - failedScenarios) + "/" + totalScenarios
                + " scenarios PASS, " + totalChecks + " checks");
        if (failedScenarios > 0) {
            System.exit(1);
        }
    }

    private boolean run(Scenario scenario) {
        Map<String, Session> sessions = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int scenarioChecks = 0;
        try {
            for (Step step : scenario.steps) {
                if (step.closeSession != null) {
                    Session session = sessions.remove(step.closeSession);
                    if (session == null) {
                        failures.add("line " + step.line + ": session " + step.closeSession + " is not open");
                    } else {
                        processor.closeSession(session.sessionId());
                        log("  [" + step.closeSession + "] (disconnect)");
                    }
                    continue;
                }
                Session session = sessions.computeIfAbsent(step.session, name -> processor.openSession());
                QueryResult result = processor.execute(step.sql, session);
                if (verbose) {
                    log("  [" + step.session + "] " + step.sql.replace('\n', ' '));
                    log("      " + ResultPrinter.format(result).trim().replace("\n", "\n      "));
                }
                List<String> mismatches = verify(step, result);
                scenarioChecks += step.checkCount();
                for (String mismatch : mismatches) {
                    failures.add("line " + step.line + " [" + step.session + "] " + oneLine(step.sql)
                            + ": " + mismatch + "; got " + describe(result));
                }
            }
        } catch (RuntimeException e) {
            failures.add("driver error: " + e);
        } finally {
            for (Session session : sessions.values()) {
                processor.closeSession(session.sessionId());
            }
        }
        checks += scenarioChecks;
        if (failures.isEmpty()) {
            System.out.println("PASS " + scenario.name + " (" + scenarioChecks + " checks)");
            return true;
        }
        System.out.println("FAIL " + scenario.name);
        for (String failure : failures) {
            System.out.println("       " + failure);
        }
        return false;
    }

    private List<String> verify(Step step, QueryResult result) {
        List<String> mismatches = new ArrayList<>();
        if (step.expects.isEmpty() && step.expectedRows.isEmpty()) {
            if (result.kind() == ResultKind.ERROR) {
                mismatches.add("expected no error");
            }
            return mismatches;
        }
        for (String expect : step.expects) {
            String kind = firstWord(expect);
            String argument = expect.substring(kind.length()).trim();
            switch (kind) {
                case "ok" -> {
                    if (result.kind() == ResultKind.ERROR) {
                        mismatches.add("expected no error");
                    }
                }
                case "rows" -> {
                    if (result.kind() != ResultKind.ROWS || result.rows().size() != Integer.parseInt(argument)) {
                        mismatches.add("expected " + argument + " row(s)");
                    }
                }
                case "columns" -> {
                    List<String> expected = splitList(argument, ",");
                    List<String> actual = result.columns().stream()
                            .map(ResultColumn::name)
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();
                    if (result.kind() != ResultKind.ROWS || !actual.equals(lower(expected))) {
                        mismatches.add("expected columns " + expected + " but columns were " + actual);
                    }
                }
                case "affected" -> {
                    if (result.kind() != ResultKind.AFFECTED_ROWS
                            || result.affectedRows() != Long.parseLong(argument)) {
                        mismatches.add("expected " + argument + " affected row(s)");
                    }
                }
                case "transaction" -> {
                    boolean matches = result.kind() == ResultKind.TRANSACTION
                            && result.transaction()
                                    .map(t -> t.status().name().equalsIgnoreCase(argument))
                                    .orElse(false);
                    if (!matches) {
                        mismatches.add("expected transaction " + argument);
                    }
                }
                case "error" -> {
                    List<String> parts = splitList(argument, " ");
                    String code = parts.get(parts.size() - 1);
                    String component = parts.size() > 1 ? parts.get(0) : null;
                    ErrorInfo error = result.error().orElse(null);
                    boolean matches = result.kind() == ResultKind.ERROR
                            && error != null
                            && error.code().name().equalsIgnoreCase(code)
                            && (component == null || error.component().name().equalsIgnoreCase(component));
                    if (!matches) {
                        mismatches.add("expected error " + argument);
                    }
                }
                case "message" -> {
                    if (!result.message().toLowerCase(Locale.ROOT).contains(argument.toLowerCase(Locale.ROOT))) {
                        mismatches.add("expected message containing \"" + argument + "\"");
                    }
                }
                default -> mismatches.add("unknown expectation " + kind);
            }
        }
        if (!step.expectedRows.isEmpty()) {
            List<List<String>> actual = result.rows().stream()
                    .map(row -> row.stream().map(QP1ScriptDriver::render).toList())
                    .toList();
            if (result.kind() != ResultKind.ROWS || !actual.equals(step.expectedRows)) {
                mismatches.add("expected rows " + step.expectedRows + " but rows were " + actual);
            }
        }
        return mismatches;
    }

    private static String render(Value value) {
        return ResultPrinter.formatValue(value);
    }

    private static String describe(QueryResult result) {
        return switch (result.kind()) {
            case ROWS -> result.rows().size() + " row(s)";
            case AFFECTED_ROWS -> result.affectedRows() + " affected";
            case TRANSACTION -> "transaction " + result.transaction().map(t -> t.status().name()).orElse("?");
            case ERROR -> result.error().map(ResultPrinter::formatError).orElse("ERROR: " + result.message());
        };
    }

    private void log(String line) {
        if (verbose) {
            System.out.println(line);
        }
    }

    private static final class Scenario {
        final String name;
        final List<Step> steps = new ArrayList<>();

        Scenario(String name) {
            this.name = name;
        }
    }

    private static final class Step {
        final int line;
        final String session;
        final String sql;
        final String closeSession;
        final List<String> expects = new ArrayList<>();
        final List<List<String>> expectedRows = new ArrayList<>();

        Step(int line, String session, String sql, String closeSession) {
            this.line = line;
            this.session = session;
            this.sql = sql;
            this.closeSession = closeSession;
        }

        int checkCount() {
            return Math.max(1, expects.size() + (expectedRows.isEmpty() ? 0 : 1));
        }
    }

    private static final class ScriptException extends Exception {
        private static final long serialVersionUID = 1L;

        ScriptException(int line, String message) {
            super("line " + line + ": " + message);
        }
    }

    private static List<Scenario> parse(Path script) throws IOException, ScriptException {
        List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
        List<Scenario> scenarios = new ArrayList<>();
        Scenario current = null;
        Step last = null;
        String session = "A";
        StringBuilder sql = new StringBuilder();
        int sqlLine = 0;
        boolean inLiteral = false;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = lines.get(i).replace("﻿", "");
            String trimmed = line.trim();
            if (!inLiteral && trimmed.startsWith("--")) {
                if (!sql.toString().isBlank()) {
                    throw new ScriptException(lineNo, "directive inside an unfinished statement (missing ';')");
                }
                String directive = trimmed.substring(2).trim();
                if (directive.startsWith("scenario:")) {
                    current = new Scenario(value(directive));
                    scenarios.add(current);
                    session = "A";
                    last = null;
                } else if (directive.startsWith("session:")) {
                    session = requireValue(directive, lineNo);
                } else if (directive.startsWith("close-session:")) {
                    requireScenario(current, lineNo);
                    current.steps.add(new Step(lineNo, null, null, requireValue(directive, lineNo)));
                    last = null;
                } else if (directive.startsWith("expect-row:")) {
                    requireStatement(last, lineNo);
                    last.expectedRows.add(splitList(value(directive), "\\|"));
                } else if (directive.startsWith("expect:")) {
                    requireStatement(last, lineNo);
                    String expect = requireValue(directive, lineNo);
                    if (!EXPECTATION_KINDS.contains(firstWord(expect))) {
                        throw new ScriptException(lineNo, "unknown expectation '" + firstWord(expect) + "'");
                    }
                    last.expects.add(expect);
                }
                continue;
            }
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '\'') {
                    inLiteral = !inLiteral;
                }
                if (ch == ';' && !inLiteral) {
                    String statement = sql.toString().trim();
                    sql.setLength(0);
                    if (statement.isEmpty()) {
                        continue;
                    }
                    requireScenario(current, sqlLine);
                    last = new Step(sqlLine, session, statement, null);
                    current.steps.add(last);
                    continue;
                }
                if (sql.toString().isBlank() && !Character.isWhitespace(ch)) {
                    sqlLine = lineNo;
                }
                sql.append(ch);
            }
            sql.append('\n');
        }
        if (!sql.toString().isBlank()) {
            throw new ScriptException(sqlLine, "statement is missing its terminating ';'");
        }
        return scenarios;
    }

    private static void requireScenario(Scenario scenario, int line) throws ScriptException {
        if (scenario == null) {
            throw new ScriptException(line, "statement or directive before the first '-- scenario:'");
        }
    }

    private static void requireStatement(Step step, int line) throws ScriptException {
        if (step == null) {
            throw new ScriptException(line, "expectation without a preceding statement");
        }
    }

    private static String requireValue(String directive, int line) throws ScriptException {
        String value = value(directive);
        if (value.isEmpty()) {
            throw new ScriptException(line, "directive '" + directive + "' needs a value");
        }
        return value;
    }

    private static String value(String directive) {
        return directive.substring(directive.indexOf(':') + 1).trim();
    }

    private static String firstWord(String text) {
        int space = text.indexOf(' ');
        return (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static List<String> splitList(String text, String separatorRegex) {
        List<String> parts = new ArrayList<>();
        for (String part : text.split(separatorRegex)) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private static List<String> lower(List<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList();
    }

    private static String oneLine(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    private static Path locateDefaultScript() {
        if (Files.exists(DEFAULT_SCRIPT)) {
            return DEFAULT_SCRIPT;
        }
        Path fromModule = Path.of("test-scripts", "qp14-acceptance.sql");
        return Files.exists(fromModule) ? fromModule : DEFAULT_SCRIPT;
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            System.out.println("warning: could not delete " + dir + ": " + e.getMessage());
        }
    }
}
