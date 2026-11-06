package sqlike.shared;

public interface RecordCursor extends AutoCloseable {
    boolean hasNext();
    Record next();
    @Override void close();
}
