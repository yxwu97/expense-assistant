package expenseassistant.casework;

public final class CaseException extends RuntimeException {
    private final String code;
    private final String relatedId;

    public CaseException(String code, String message) {
        this(code, message, null, null);
    }

    public CaseException(String code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public CaseException(String code, String message, String relatedId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.relatedId = relatedId;
    }

    public String code() {
        return code;
    }

    public String relatedId() {
        return relatedId;
    }
}
