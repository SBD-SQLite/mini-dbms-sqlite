package sqlike.qp;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import sqlike.contract.IConcurrencyControlManager;
import sqlike.contract.IFailureRecoveryManager;
import sqlike.contract.IQueryOptimizer;
import sqlike.shared.AccessResponse;
import sqlike.shared.AccessStatus;
import sqlike.shared.ColumnRef;
import sqlike.shared.ComponentType;
import sqlike.shared.DataChange;
import sqlike.shared.DataType;
import sqlike.shared.ErrorCode;
import sqlike.shared.ErrorInfo;
import sqlike.shared.ParsedQuery;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.ProtocolType;
import sqlike.shared.QueryPlan;
import sqlike.shared.QueryResult;
import sqlike.shared.RecoveryResult;
import sqlike.shared.RecoveryTarget;
import sqlike.shared.ResourceId;
import sqlike.shared.ResultColumn;
import sqlike.shared.ResultKind;
import sqlike.shared.ScanSpec;
import sqlike.shared.Session;
import sqlike.shared.StatementType;
import sqlike.shared.TransactionAction;
import sqlike.shared.TransactionCommand;
import sqlike.shared.TransactionResponse;
import sqlike.shared.TransactionStatus;
import sqlike.shared.Value;

/**
 * Interactive M1 driver for the QP1 facade. QO, CCM, FRM, and plan execution are fixtures;
 * only QueryProcessor, SessionManager, StatementReader, and ResultPrinter are real.
 * Type SQL ending with ';', \test to run the scenarios, \help for fixtures, \q to quit.
 * Run with --test to execute the scenarios without the prompt.
 */
public class QP1Driver {
    private static int scenarios;
    private static int checks;

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("--test")) {
            runScenarios();
            return;
        }
        Fixture fixture = Fixture.ready();
        Session session = fixture.processor.openSession();
        System.out.println("SQLike QP1 driver (M1 fixtures). Session " + session.sessionId());
        System.out.println("Type \\help for fixture statements, \\test for scenarios, \\q to quit.");
        StatementReader reader = new StatementReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("sqlike> ");
            System.out.flush();
            Optional<String> next = reader.nextStatement();
            if (next.isEmpty()) {
                break;
            }
            String input = next.get();
            if (StatementReader.isClientCommand(input)) {
                String command = input.substring(1).trim().toLowerCase(Locale.ROOT);
                if (command.equals("q") || command.equals("quit")) {
                    break;
                } else if (command.equals("test")) {
                    runScenarios();
                } else if (command.equals("help")) {
                    printHelp();
                } else {
                    System.out.println("Unknown command: " + input);
                }
                continue;
            }
            ResultPrinter.print(fixture.processor.execute(input, session), System.out);
        }
        fixture.processor.closeSession(session.sessionId());
        System.out.println("Bye.");
    }

    private static void printHelp() {
        System.out.println("Fixture statements (case and spacing are ignored):");
        for (String sql : MockOptimizer.FIXTURES) {
            System.out.println("  " + sql + ";");
        }
        System.out.println("  BEGIN TRANSACTION;  COMMIT;  ABORT;");
        System.out.println("Any other statement returns a QO SYNTAX_ERROR.");
    }

    private static void runScenarios() {
        scenarios = 0;
        checks = 0;
        run("startup recovery before READY", QP1Driver::startup);
        run("auto-commit SELECT and DML", QP1Driver::autoCommit);
        run("explicit BEGIN ... COMMIT", QP1Driver::explicitCommit);
        run("explicit BEGIN ... ABORT and invalid transaction commands", QP1Driver::explicitAbort);
        run("error mapping without stack traces", QP1Driver::errors);
        run("rollback on failure and CCM-aborted transaction", QP1Driver::failures);
        run("session isolation and close", QP1Driver::sessions);
        run("executePlan with given txId", QP1Driver::executePlan);
        run("StatementReader splitting", QP1Driver::reader);
        run("ResultPrinter formatting", QP1Driver::printer);
        System.out.println("QP1 M1: " + scenarios + "/10 scenarios, " + checks + " checks PASS (fixtures only)");
    }

    private static void startup() {
        Fixture f = new Fixture();
        QueryResult early = f.processor.executePlan(MockOptimizer.plan("select * from student"), 1);
        check(early.kind() == ResultKind.ERROR && code(early) == ErrorCode.INVALID_STATE, "rejects queries before initialize");
        f.processor.initialize();
        check(f.processor.getState() == SystemState.READY, "READY after initialize");
        check(f.recovery.recovered && f.ccm.calls.equals(List.of("initAfterRecovery 41")),
                "recover() runs before initAfterRecovery(lastTxId)");
        f.processor.initialize();
        check(f.ccm.calls.size() == 1, "initialize is idempotent");

        Fixture broken = new Fixture();
        broken.recovery.fail = true;
        expect(IllegalStateException.class, broken.processor::initialize);
        check(broken.processor.getState() == SystemState.FAILED, "FAILED when recovery fails");
    }

    private static void autoCommit() {
        Fixture f = Fixture.ready();
        Session s = f.processor.openSession();
        QueryResult rows = f.processor.execute("SELECT *   FROM student", s);
        check(rows.kind() == ResultKind.ROWS && rows.rows().size() == 2, "SELECT returns fixture rows");
        check(rows.columns().size() == 2 && rows.columns().get(0).name().equals("id"), "SELECT keeps output columns");
        check(f.ccm.calls.equals(List.of("initAfterRecovery 41", "begin 42", "commit 42")), "implicit begin/commit around SELECT");
        check(f.executor.contexts.get(0).txId() == 42 && f.executor.contexts.get(0).sessionId().isPresent(),
                "plan runs with the implicit txId and session id");

        QueryResult insert = f.processor.execute("insert into student values (3, 'Cici')", s);
        check(insert.kind() == ResultKind.AFFECTED_ROWS && insert.affectedRows() == 1, "INSERT reports affected rows");
        check(f.ccm.calls.subList(3, 5).equals(List.of("begin 43", "commit 43")), "each statement gets its own transaction");
        check(f.sessions.requireSession(s.sessionId()).getCurrentTxId().isEmpty(), "session has no transaction after auto-commit");
    }

    private static void explicitCommit() {
        Fixture f = Fixture.ready();
        Session s = f.processor.openSession();
        QueryResult begin = f.processor.execute("BEGIN TRANSACTION", s);
        check(begin.kind() == ResultKind.TRANSACTION && begin.transaction().get().status() == TransactionStatus.ACTIVE,
                "BEGIN returns ACTIVE transaction");
        check(f.sessions.requireSession(s.sessionId()).getCurrentTxId().equals(Optional.of(42L)), "session holds txId");
        f.processor.execute("insert into student values (3, 'Cici')", s);
        f.processor.execute("update student set name = 'Budi' where id = 1", s);
        check(f.executor.contexts.stream().allMatch(c -> c.txId() == 42), "statements reuse the explicit txId");
        check(f.ccm.calls.equals(List.of("initAfterRecovery 41", "begin 42")), "no implicit begin/commit inside BEGIN");
        QueryResult commit = f.processor.execute("commit", s);
        check(commit.kind() == ResultKind.TRANSACTION && commit.transaction().get().status() == TransactionStatus.COMMITTED,
                "COMMIT returns COMMITTED");
        check(f.sessions.requireSession(s.sessionId()).getCurrentTxId().isEmpty(), "COMMIT clears session txId");
    }

    private static void explicitAbort() {
        Fixture f = Fixture.ready();
        Session s = f.processor.openSession();
        f.processor.execute("begin transaction", s);
        QueryResult again = f.processor.execute("begin transaction", s);
        check(code(again) == ErrorCode.INVALID_STATE, "nested BEGIN rejected");
        f.processor.execute("delete from student where id = 1", s);
        QueryResult abort = f.processor.execute("ABORT", s);
        check(abort.transaction().get().status() == TransactionStatus.ABORTED, "ABORT returns ABORTED");
        check(f.ccm.calls.contains("abort 42"), "ABORT delegated to CCM");
        check(code(f.processor.execute("COMMIT", s)) == ErrorCode.INVALID_STATE, "COMMIT without transaction rejected");
        check(code(f.processor.execute("ABORT", s)) == ErrorCode.INVALID_STATE, "ABORT without transaction rejected");

        f.ccm.rejectCommit = true;
        f.processor.execute("BEGIN TRANSACTION", s);
        QueryResult rejected = f.processor.execute("COMMIT", s);
        check(code(rejected) == ErrorCode.TRANSACTION_ABORTED, "CCM commit rejection becomes TRANSACTION_ABORTED");
        check(f.sessions.requireSession(s.sessionId()).getCurrentTxId().isEmpty(), "failed COMMIT still clears session txId");
    }

    private static void errors() {
        Fixture f = Fixture.ready();
        Session s = f.processor.openSession();
        QueryResult syntax = f.processor.execute("SELEC * FORM student", s);
        check(syntax.kind() == ResultKind.ERROR && component(syntax) == ComponentType.QO
                && code(syntax) == ErrorCode.SYNTAX_ERROR, "QO syntax error forwarded with component QO");
        check(code(f.processor.execute("select * from missing", s)) == ErrorCode.NOT_FOUND, "unknown table is NOT_FOUND");
        check(code(f.processor.execute("   ", s)) == ErrorCode.SYNTAX_ERROR, "blank SQL rejected");
        check(code(f.processor.execute("select * from student", null)) == ErrorCode.INVALID_STATE, "null session rejected");
        check(code(f.processor.execute("select * from student", new Session("nope", Optional.empty())))
                == ErrorCode.INVALID_STATE, "unknown session rejected");
        QueryResult unsupported = f.processor.execute("create index idx on student(id)", s);
        check(code(unsupported) == ErrorCode.UNSUPPORTED_OPERATION, "UnsupportedOperationException mapped");
        check(!unsupported.message().contains("at sqlike."), "no stack trace in message");
        check(f.ccm.calls.equals(List.of("initAfterRecovery 41", "begin 42", "abort 42")),
                "only the executed statement opened a transaction, aborted after its error");
    }

    private static void failures() {
        Fixture f = Fixture.ready();
        Session s = f.processor.openSession();
        f.processor.execute("BEGIN TRANSACTION", s);
        QueryResult bad = f.processor.execute("create index idx on student(id)", s);
        check(code(bad) == ErrorCode.UNSUPPORTED_OPERATION, "statement error inside explicit transaction");
        check(f.sessions.requireSession(s.sessionId()).getCurrentTxId().isPresent(),
                "explicit transaction survives a non-fatal statement error");
        QueryResult conflict = f.processor.execute("update student set name = 'conflict' where id = 2", s);
        check(code(conflict) == ErrorCode.TRANSACTION_ABORTED && component(conflict) == ComponentType.CCM,
                "CCM abort surfaces as TRANSACTION_ABORTED");
        check(f.sessions.requireSession(s.sessionId()).getCurrentTxId().isEmpty(),
                "session falls back to auto-commit after CCM abort");
        QueryResult after = f.processor.execute("select * from student", s);
        check(after.kind() == ResultKind.ROWS, "session still usable after abort");
    }

    private static void sessions() {
        Fixture f = Fixture.ready();
        Session a = f.processor.openSession();
        Session b = f.processor.openSession();
        check(!a.sessionId().equals(b.sessionId()), "session ids are unique");
        f.processor.execute("BEGIN TRANSACTION", a);
        f.processor.execute("select * from student", b);
        check(f.sessions.requireSession(a.sessionId()).getCurrentTxId().equals(Optional.of(42L))
                && f.sessions.requireSession(b.sessionId()).getCurrentTxId().isEmpty(), "transactions are per session");
        f.processor.closeSession(a.sessionId());
        check(f.ccm.calls.contains("abort 42"), "closing a session aborts its open transaction");
        check(code(f.processor.execute("select * from student", a)) == ErrorCode.INVALID_STATE, "closed session rejected");
        check(f.sessions.activeSessions().size() == 1, "closed session removed");
        f.processor.shutdown();
        check(f.processor.getState() == SystemState.STOPPED && f.sessions.activeSessions().isEmpty(), "shutdown closes all sessions");
    }

    private static void executePlan() {
        Fixture f = Fixture.ready();
        QueryResult rows = f.processor.executePlan(MockOptimizer.plan("select * from student"), 77);
        check(rows.kind() == ResultKind.ROWS && f.executor.contexts.get(0).txId() == 77, "executePlan uses caller txId");
        check(f.ccm.calls.size() == 1, "executePlan does not open its own transaction");
        check(code(f.processor.executePlan(null, 77)) == ErrorCode.INVALID_VALUE, "null plan mapped to ERROR");
    }

    private static void reader() {
        List<String> all = readAll("SELECT * FROM a;\n  select 'x;y' from b ; ;\nINSERT INTO c VALUES ('it''s;');\n\\q\nselect\n  1");
        check(all.equals(List.of("SELECT * FROM a", "select 'x;y' from b", "INSERT INTO c VALUES ('it''s;')", "\\q", "select\n  1")),
                "splits on ';' outside literals, keeps commands and trailing text");
        check(readAll("  \n ;; ").isEmpty(), "blank input yields no statements");
        check(StatementReader.isClientCommand("\\test") && !StatementReader.isClientCommand("select 1"), "client command detection");
        expect(IllegalArgumentException.class, () -> new StatementReader(null));
    }

    private static void printer() {
        Fixture f = Fixture.ready();
        Session s = f.processor.openSession();
        String table = ResultPrinter.format(f.processor.execute("select * from student", s));
        check(table.contains("| id | name |") && table.contains("| 1  | Ani  |") && table.contains("2 row(s) returned"),
                "rows rendered as table");
        String error = ResultPrinter.format(f.processor.execute("SELEC", s));
        check(error.startsWith("ERROR [QO SYNTAX_ERROR]"), "error rendered with component and code");
        check(ResultPrinter.formatValue(null).equals("NULL"), "null value rendered as NULL");
    }

    private static List<String> readAll(String text) {
        StatementReader reader = new StatementReader(new StringReader(text));
        List<String> out = new ArrayList<>();
        try {
            Optional<String> next = reader.nextStatement();
            while (next.isPresent()) {
                out.add(next.get());
                next = reader.nextStatement();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static ErrorCode code(QueryResult result) {
        return result.error().map(ErrorInfo::code).orElse(null);
    }

    private static ComponentType component(QueryResult result) {
        return result.error().map(ErrorInfo::component).orElse(null);
    }

    private static void run(String name, Runnable scenario) {
        scenario.run();
        scenarios++;
        System.out.println("PASS " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        checks++;
    }

    private static void expect(Class<? extends RuntimeException> expected, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            check(expected.isInstance(e), "expected " + expected.getSimpleName() + " but got " + e.getClass().getSimpleName());
            return;
        }
        throw new AssertionError("FAIL: expected " + expected.getSimpleName());
    }

    /** Wires the real facade to fresh fixtures. */
    private static final class Fixture {
        final MockOptimizer optimizer = new MockOptimizer();
        final MockConcurrencyControl ccm = new MockConcurrencyControl();
        final MockRecovery recovery = new MockRecovery();
        final MockPlanExecutor executor = new MockPlanExecutor();
        final SessionManager sessions = new SessionManager();
        final QueryProcessor processor = new QueryProcessor(optimizer, ccm, recovery, executor, sessions);

        static Fixture ready() {
            Fixture fixture = new Fixture();
            fixture.processor.initialize();
            return fixture;
        }
    }

    private static final class MockOptimizer implements IQueryOptimizer {
        static final List<String> FIXTURES = List.of(
                "select * from student",
                "select * from missing",
                "insert into student values (3, 'Cici')",
                "update student set name = 'Budi' where id = 1",
                "update student set name = 'conflict' where id = 2",
                "delete from student where id = 1",
                "create index idx on student(id)");

        @Override
        public ParsedQuery parseQuery(String sql) {
            String key = normalize(sql);
            switch (key) {
                case "begin":
                case "begin transaction":
                    return transaction(StatementType.BEGIN, TransactionAction.BEGIN);
                case "commit":
                    return transaction(StatementType.COMMIT, TransactionAction.COMMIT);
                case "abort":
                case "rollback":
                    return transaction(StatementType.ABORT, TransactionAction.ABORT);
                case "select * from missing":
                    throw new QpException(new ErrorInfo(ErrorCode.NOT_FOUND, ComponentType.QO,
                            "Table 'missing' does not exist", Optional.of(14)));
                default:
                    break;
            }
            for (String fixture : FIXTURES) {
                if (normalize(fixture).equals(key)) {
                    PlanNode root = plan(fixture).root();
                    return new ParsedQuery(statementType(root.kind()), Optional.of(root), Optional.empty());
                }
            }
            throw new QpException(new ErrorInfo(ErrorCode.SYNTAX_ERROR, ComponentType.QO,
                    "Unrecognized statement near '" + sql.trim().split("\\s+")[0] + "'", Optional.of(0)));
        }

        @Override
        public QueryPlan optimizeQuery(ParsedQuery query) {
            return new QueryPlan(query.root().orElseThrow(), 1.0);
        }

        @Override
        public double getCost(QueryPlan plan) {
            return plan.estimatedCost();
        }

        static QueryPlan plan(String sql) {
            String key = normalize(sql);
            PlanNodeKind kind;
            String table = "student";
            if (key.startsWith("select")) {
                kind = PlanNodeKind.SCAN;
            } else if (key.startsWith("insert")) {
                kind = PlanNodeKind.INSERT;
            } else if (key.startsWith("update")) {
                kind = PlanNodeKind.UPDATE;
                table = key.contains("conflict") ? "conflict" : table;
            } else if (key.startsWith("delete")) {
                kind = PlanNodeKind.DELETE;
            } else {
                kind = PlanNodeKind.CREATE_INDEX;
            }
            List<ResultColumn> columns = kind == PlanNodeKind.SCAN
                    ? List.of(column("id", DataType.INTEGER), column("name", DataType.VARCHAR))
                    : List.of();
            PlanNode root = new PlanNode(kind, List.of(), columns, Optional.of(2.0),
                    Optional.of(new ScanSpec(table, Optional.empty(), Optional.empty())),
                    null, List.of(), null, null, null, null);
            return new QueryPlan(root, 1.0);
        }

        private static ResultColumn column(String name, DataType type) {
            return new ResultColumn(name, Optional.of(ColumnRef.of("student", name)), type);
        }

        private static StatementType statementType(PlanNodeKind kind) {
            switch (kind) {
                case INSERT: return StatementType.INSERT;
                case UPDATE: return StatementType.UPDATE;
                case DELETE: return StatementType.DELETE;
                case CREATE_INDEX: return StatementType.CREATE_INDEX;
                default: return StatementType.SELECT;
            }
        }

        private static ParsedQuery transaction(StatementType type, TransactionAction action) {
            return new ParsedQuery(type, Optional.empty(), Optional.of(new TransactionCommand(action)));
        }

        private static String normalize(String sql) {
            return sql.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
    }

    private static final class MockPlanExecutor implements PlanExecutor {
        final List<ExecutionContext> contexts = new ArrayList<>();

        @Override
        public QueryResult execute(QueryPlan plan, ExecutionContext context) {
            if (plan == null) {
                throw new IllegalArgumentException("QueryPlan cannot be null in MockPlanExecutor");
            }
            contexts.add(context);
            PlanNode root = plan.root();
            String table = root.scan().map(ScanSpec::tableName).orElse("");
            switch (root.kind()) {
                case SCAN:
                    List<List<Value>> rows = List.of(List.of(integer(1), text("Ani")), List.of(integer(2), text("Budi")));
                    return new QueryResult(ResultKind.ROWS, root.outputColumns(), rows, 0,
                            rows.size() + " row(s) returned", Optional.empty());
                case CREATE_INDEX:
                    throw new UnsupportedOperationException("CREATE INDEX is not available in M1");
                default:
                    if (table.equals("conflict")) {
                        throw new QpException(new ErrorInfo(ErrorCode.TRANSACTION_ABORTED, ComponentType.CCM,
                                "Transaction " + context.txId() + " aborted by concurrency control", Optional.empty()));
                    }
                    return new QueryResult(ResultKind.AFFECTED_ROWS, List.of(), List.of(), 1,
                            "1 row(s) affected", Optional.empty());
            }
        }

        private static Value integer(int value) {
            return new Value(DataType.INTEGER, Optional.of(value), Optional.empty(), Optional.empty());
        }

        private static Value text(String value) {
            return new Value(DataType.VARCHAR, Optional.empty(), Optional.empty(), Optional.of(value));
        }
    }

    private static final class MockConcurrencyControl implements IConcurrencyControlManager {
        final List<String> calls = new ArrayList<>();
        final Map<Long, TransactionStatus> transactions = new HashMap<>();
        boolean rejectCommit;
        private long nextTxId;

        @Override
        public TransactionResponse beginTransaction() {
            long txId = ++nextTxId;
            transactions.put(txId, TransactionStatus.ACTIVE);
            calls.add("begin " + txId);
            return response(txId, TransactionStatus.ACTIVE);
        }

        @Override
        public AccessResponse requestRead(long txId, ResourceId resource) {
            return new AccessResponse(AccessStatus.GRANTED, Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public AccessResponse requestWrite(long txId, DataChange change) {
            return new AccessResponse(AccessStatus.GRANTED, Optional.of(0L), Optional.empty(), Optional.empty());
        }

        @Override
        public TransactionResponse commit(long txId) {
            calls.add("commit " + txId);
            TransactionStatus status = rejectCommit ? TransactionStatus.ABORTED : TransactionStatus.COMMITTED;
            transactions.put(txId, status);
            return response(txId, status);
        }

        @Override
        public TransactionResponse abort(long txId) {
            calls.add("abort " + txId);
            transactions.put(txId, TransactionStatus.ABORTED);
            return response(txId, TransactionStatus.ABORTED);
        }

        @Override
        public void setProtocol(ProtocolType protocol) {
        }

        @Override
        public void initAfterRecovery(long lastTxId) {
            calls.add("initAfterRecovery " + lastTxId);
            nextTxId = lastTxId;
        }

        @Override
        public void pauseNewTransactions() {
        }

        @Override
        public void awaitNoActiveTransactions() {
        }

        @Override
        public void resumeTransactions() {
        }

        private TransactionResponse response(long txId, TransactionStatus status) {
            return new TransactionResponse(txId, txId, status, Optional.empty());
        }
    }

    private static final class MockRecovery implements IFailureRecoveryManager {
        boolean recovered;
        boolean fail;

        @Override
        public long logBegin(long txId) {
            return 0;
        }

        @Override
        public long logChange(long txId, DataChange change) {
            return 0;
        }

        @Override
        public void logCommit(long txId) {
        }

        @Override
        public void logAbort(long txId) {
        }

        @Override
        public void ensureDurable(long lsn) {
        }

        @Override
        public void rollback(long txId) {
        }

        @Override
        public RecoveryResult recover() {
            if (fail) {
                throw new IllegalStateException("WAL is corrupted");
            }
            recovered = true;
            return new RecoveryResult(41, 41, 0, Optional.empty());
        }

        @Override
        public void checkpoint() {
        }

        @Override
        public void recoverTo(RecoveryTarget target) {
        }
    }
}
