package expenseassistant.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;

public final class AppVersion {
    private static final String UNKNOWN = "版本未知";

    private AppVersion() {}

    public static String displayText() {
        return displayText(value());
    }

    public static Optional<String> value() {
        return readResource(AppVersion.class.getResourceAsStream("/expenseassistant/build-version.properties"));
    }

    static String displayText(Optional<String> version) {
        return version.map(value -> "版本 " + value).orElse(UNKNOWN);
    }

    static Optional<String> readResource(InputStream resource) {
        if (resource == null) return Optional.empty();
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            var properties = new Properties();
            properties.load(reader);
            String version = properties.getProperty("version", "").strip();
            return Optional.of(version).filter(value -> !value.isEmpty() && !value.contains("${"));
        } catch (IOException | IllegalArgumentException error) {
            return Optional.empty();
        }
    }
}
