package sqlike.storage;

public class StorageException extends RuntimeException {
    private final StorageErrorCode code;

    public StorageException(StorageErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public StorageException(StorageErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public StorageErrorCode getCode() {
        return code;
    }
}