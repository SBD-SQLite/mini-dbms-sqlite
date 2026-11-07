package sqlike.qp;

/**
 * Streaming sink for query execution results matching CLASS DIAGRAM FIX (Tab 4).
 */
public interface ResultSink {
    void onSchema(Schema schema);

    void onRow(Row row);

    void onFinish(ExecutionSummary summary);
}

