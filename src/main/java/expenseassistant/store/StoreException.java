package expenseassistant.store;

import java.nio.file.Path;

public final class StoreException extends RuntimeException {
    private final String code;
    private final Path path;
    private final Path backup;

    public StoreException(String code, String message, Path path, Throwable cause) {
        this(code, message, path, null, cause);
    }

    public StoreException(String code, String message, Path path, Path backup, Throwable cause) {
        super(message + "（" + path + "）" + (backup == null ? "" : "\n已校验备份 " + backup), cause);
        this.code = code;
        this.path = path;
        this.backup = backup;
    }

    public String code() {
        return code;
    }

    public Path path() {
        return path;
    }

    public Path backup() {
        return backup;
    }
}
