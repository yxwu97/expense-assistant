import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class ReleaseVersion {
    private static final Pattern APPLICATION = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.([0-9]{8})([0-9]{3})");
    private static final Pattern ENTRY = Pattern.compile("([^=]+)=([^,]+),(active|abandoned)");
    private static final Pattern NATIVE = Pattern.compile(
            "([1-9][0-9]{0,2})\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)");

    private ReleaseVersion() {}

    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException("Usage: ReleaseVersion <ledger> <app-version>");
            validateApplication(args[1]);
            Entry selected = readLedger(Path.of(args[0])).get(args[1]);
            if (selected == null) throw new IllegalArgumentException("Version is not registered: " + args[1]);
            if (selected.abandoned()) throw new IllegalArgumentException("Version is abandoned: " + args[1]);
            System.out.println(selected.nativeVersion());
        } catch (IOException | IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    private static Map<String, Entry> readLedger(Path path) throws IOException {
        var entries = new LinkedHashMap<String, Entry>();
        int previous = 0;
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) continue;
            try {
                var match = ENTRY.matcher(stripped);
                if (!match.matches()) throw new IllegalArgumentException("Expected application=native,active|abandoned");
                String application = match.group(1);
                validateApplication(application);
                if (entries.containsKey(application)) {
                    throw new IllegalArgumentException("Duplicate application version: " + application);
                }
                String nativeVersion = match.group(2);
                int order = nativeOrder(nativeVersion);
                if (order <= previous) {
                    throw new IllegalArgumentException("Native version must be unique and increasing: " + nativeVersion);
                }
                previous = order;
                entries.put(application, new Entry(nativeVersion, match.group(3).equals("abandoned")));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(path + ":" + lineNumber + ": " + error.getMessage(), error);
            }
        }
        return entries;
    }

    private static void validateApplication(String version) {
        var match = APPLICATION.matcher(version);
        if (!match.matches() || match.group(2).equals("000")) {
            throw new IllegalArgumentException("Invalid application version or release sequence: " + version);
        }
        try {
            var date = LocalDate.parse(match.group(1), DateTimeFormatter.BASIC_ISO_DATE);
            if (date.getYear() == 0) throw new IllegalArgumentException("Invalid release date: " + version);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Invalid release date: " + version, error);
        }
    }

    private static int nativeOrder(String version) {
        var match = NATIVE.matcher(version);
        if (!match.matches() || Integer.parseInt(match.group(1)) > 255) {
            throw new IllegalArgumentException("Native version must be in 1.0.0 through 255.99.99: " + version);
        }
        int major = Integer.parseInt(match.group(1));
        int minor = Integer.parseInt(match.group(2));
        int patch = Integer.parseInt(match.group(3));
        return (major * 100 + minor) * 100 + patch;
    }

    private record Entry(String nativeVersion, boolean abandoned) {}
}
