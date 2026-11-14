package sqlike.qp.stub;

import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;
import java.util.Optional;
import sqlike.qp.QpConfiguration;
import sqlike.qp.QueryProcessor;
import sqlike.qp.SessionManager;
import sqlike.qp.SessionState;
import sqlike.qp.port.QpContracts.RecoveryReport;
import sqlike.shared.ColumnSchema;
import sqlike.shared.DataType;
import sqlike.shared.TableSchema;

/**
 * Real QP pipeline over stub SM/CCM/FRM, used by QpServerMain and QP1ScriptDriver.
 * A new data directory is seeded with the student and enrollment sample tables.
 * TODO(M3): replace StubStorage/StubTransactions with the SM, CCM, and FRM implementations.
 */
public final class StubQpRuntime {
    public final StubStorage storage;
    public final StubTransactions transactions;
    public final StubQueryRunner runner;
    public final QueryProcessor processor;

    private StubQpRuntime(
            StubStorage storage,
            StubTransactions transactions,
            StubQueryRunner runner,
            QueryProcessor processor) {
        this.storage = storage;
        this.transactions = transactions;
        this.runner = runner;
        this.processor = processor;
    }

    public static StubQpRuntime open(Path dataDir, QpConfiguration config) {
        List<String> trace = new DiscardingTrace();
        StubStorage storage = new StubStorage(dataDir.resolve("storage.stub"), trace);
        StubTransactions transactions = new StubTransactions(storage, trace);
        boolean fresh = storage.listTables().isEmpty();
        if (fresh) {
            defineSampleTables(storage);
        }
        StubQueryRunner runner = new StubQueryRunner(storage, transactions, config);
        if (fresh) {
            seedSampleRows(runner);
        }
        QueryProcessor processor = new QueryProcessor(
                runner.optimizer,
                runner.translator,
                runner.engine,
                new SessionManager(),
                runner.transactions,
                runner.access);
        return new StubQpRuntime(storage, transactions, runner, processor);
    }

    /** Startup recovery for drivers that run without QueryServer/QpBootstrap. */
    public void recover() {
        RecoveryReport report = transactions.recover();
        if (report == null || !report.success()) {
            throw new IllegalStateException("Startup recovery failed");
        }
        transactions.initialize(report);
        processor.initialize();
    }

    private static void defineSampleTables(StubStorage storage) {
        storage.define(new TableSchema("1", "student", List.of(
                new ColumnSchema("id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("name", DataType.VARCHAR, Optional.of(30)),
                new ColumnSchema("gpa", DataType.FLOAT, Optional.empty()))));
        storage.define(new TableSchema("2", "enrollment", List.of(
                new ColumnSchema("student_id", DataType.INTEGER, Optional.empty()),
                new ColumnSchema("course", DataType.VARCHAR, Optional.of(20)))));
    }

    private static void seedSampleRows(StubQueryRunner runner) {
        SessionState loader = new SessionState();
        List<String> statements = List.of(
                "INSERT INTO student VALUES (1, 'Ani', 3.5);",
                "INSERT INTO student VALUES (2, 'Budi', 3.2);",
                "INSERT INTO student VALUES (3, 'Cici', 3.8);",
                "INSERT INTO enrollment VALUES (1, 'IF3140');",
                "INSERT INTO enrollment VALUES (2, 'IF3140');",
                "INSERT INTO enrollment VALUES (3, 'IF3130');");
        for (String sql : statements) {
            StubQueryRunner.Result result = runner.execute(loader, sql);
            if (!result.success()) {
                throw new IllegalStateException("Sample data seed failed for \"" + sql + "\": " + result.message());
            }
        }
    }

    private static final class DiscardingTrace extends AbstractList<String> {
        @Override
        public boolean add(String line) {
            return true;
        }

        @Override
        public String get(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
