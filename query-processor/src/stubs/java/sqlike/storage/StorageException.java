package sqlike.storage;

// M1 compile stub of the SM-owned checked exception; use the provider class when SM is integrated.
public class StorageException extends Exception {
    private static final long serialVersionUID = 1L;
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
