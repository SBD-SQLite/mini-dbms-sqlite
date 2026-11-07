package sqlike.qp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sqlike.shared.PlanNode;
import sqlike.shared.PlanNodeKind;
import sqlike.shared.QueryPlan;
import sqlike.shared.QueryResult;
import sqlike.shared.ResultKind;
import sqlike.shared.Value;

/**
 * ExecutionEngine driving physical Operator trees and CommandExecutors matching CLASS DIAGRAM FIX (Tab 4).
 * Also implements PlanExecutor for seamless integration with Tab 6 IQueryProcessor.
 */
public final class ExecutionEngine implements PlanExecutor {
    private final PlanTranslator translator;

    public ExecutionEngine() {
        this.translator = null;
    }

    public ExecutionEngine(PlanTranslator translator) {
        this.translator = translator;
    }

    public long executeSelect(Operator root, ExecutionContext context, ResultSink sink) {
        if (root == null) {
            throw new IllegalArgumentException("Operator root cannot be null in ExecutionEngine.executeSelect");
        }
        if (context == null) {
            throw new IllegalArgumentException("ExecutionContext cannot be null in ExecutionEngine.executeSelect");
        }
        long count = 0L;
        try {
            if (sink != null) {
                sink.onSchema(root.schema());
            }
            root.open(context);
            while (true) {
                context.check();
                Optional<RowEnvelope> next = root.next();
                if (next.isEmpty()) {
                    break;
                }
                count++;
                if (sink != null) {
                    sink.onRow(next.get().row());
                }
            }
        } catch (RuntimeException e) {
            DbmsException cause = e instanceof DbmsException dbms ? dbms : new DbmsException(e.getMessage(), e);
            closeAfterFailure(root, cause);
            throw e;
        }
        root.close();
        return count;
    }

    public long executeCommand(CommandExecutor command, ExecutionContext context) {
        if (command == null) {
            throw new IllegalArgumentException("CommandExecutor cannot be null in ExecutionEngine.executeCommand");
        }
        if (context == null) {
            throw new IllegalArgumentException("ExecutionContext cannot be null in ExecutionEngine.executeCommand");
        }
        long affected;
        try {
            affected = command.execute(context);
        } catch (RuntimeException e) {
            DbmsException cause = e instanceof DbmsException dbms ? dbms : new DbmsException(e.getMessage(), e);
            closeAfterFailure(command, cause);
            throw e;
        }
        command.close();
        return affected;
    }

    private void closeAfterFailure(AutoCloseable resource, DbmsException cause) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeError) {
            if (cause != null && closeError != cause) {
                cause.addSuppressed(closeError);
            }
        }
    }

    @Override
    public QueryResult execute(QueryPlan plan, ExecutionContext context) {
        if (plan == null || plan.root() == null) {
            throw new IllegalArgumentException("QueryPlan and its root cannot be null");
        }
        if (translator == null) {
            throw new IllegalStateException("PlanTranslator is not configured on ExecutionEngine");
        }
        PlanNode root = plan.root();
        PlanNodeKind kind = root.kind();
        if (kind == PlanNodeKind.INSERT
                || kind == PlanNodeKind.UPDATE
                || kind == PlanNodeKind.DELETE
                || kind == PlanNodeKind.CREATE_INDEX
                || kind == PlanNodeKind.CREATE_TABLE
                || kind == PlanNodeKind.DROP_TABLE) {
            CommandExecutor cmd = translator.translateCommand(root);
            long affected = executeCommand(cmd, context);
            String msg = switch (kind) {
                case CREATE_INDEX -> "Index created successfully";
                case CREATE_TABLE -> "Table " + root.createTable().map(c -> c.tableName()).orElse("") + " created successfully";
                case DROP_TABLE -> "Table " + root.dropTable().map(d -> d.tableName()).orElse("") + " dropped successfully";
                default -> affected + " row(s) affected";
            };
            return new QueryResult(ResultKind.AFFECTED_ROWS, List.of(), List.of(), affected, msg, Optional.empty());
        } else {
            Operator op = translator.translateSelect(root);
            List<List<Value>> collectedRows = new ArrayList<>();
            ResultSink collectorSink = new ResultSink() {
                @Override
                public void onSchema(Schema schema) {
                }

                @Override
                public void onRow(Row row) {
                    collectedRows.add(row.values());
                }

                @Override
                public void onFinish(ExecutionSummary summary) {
                }
            };
            long count = executeSelect(op, context, collectorSink);
            return new QueryResult(
                    ResultKind.ROWS,
                    root.outputColumns(),
                    List.copyOf(collectedRows),
                    0L,
                    count + " row(s) returned",
                    Optional.empty());
        }
    }
}

