package expenseassistant.intake;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import expenseassistant.casework.CaseException;
import expenseassistant.store.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PathPolicy {
    public static final String OUTPUT_DIRECTORY = "报销助手输出";
    public static final String MARKER_FILE = ".expense-assistant-output.json";
    private static final String APP_ID = "expense-assistant";
    private static final int MARKER_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final Database database;

    public PathPolicy(Database database) {
        this.database = database;
    }

    public record CaseFolder(Path batchRoot, Path folder) {}
    public record OutputRoot(Path path, String rootId) {}
    private record Marker(String appId, int formatVersion, String rootId) {}

    public List<Path> listCases(Path batchRoot) {
        try {
            Path batch = plainDirectory(batchRoot);
            Set<Path> exclusions = registeredOutputRoots();
            var candidates = new ArrayList<Path>();
            try (var children = Files.newDirectoryStream(batch)) {
                for (Path child : children) {
                    if (hidden(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    Path real;
                    try {
                        real = plainDirectory(child);
                    } catch (IOException inaccessible) {
                        continue;
                    }
                    if (batch.equals(real.getParent()) && !excluded(real, batch, exclusions)) {
                        candidates.add(real);
                    }
                }
            }
            candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return List.copyOf(candidates);
        } catch (IOException error) {
            throw new CaseException("INVALID_CASE_FOLDER", "批次目录不可读 " + batchRoot, error);
        }
    }

    public CaseFolder validateCase(Path batchRoot, Path caseFolder) {
        try {
            Path batch = plainDirectory(batchRoot);
            Path folder = plainDirectory(caseFolder);
            if (!batch.equals(folder.getParent()) || hidden(folder)
                    || excluded(folder, batch, registeredOutputRoots())) {
                throw new IOException("只可选择批次下的普通报销子目录");
            }
            return new CaseFolder(batch, folder);
        } catch (IOException error) {
            throw new CaseException("INVALID_CASE_FOLDER", "无效报销目录 " + caseFolder, error);
        }
    }

    public Path normalizeOutputRequest(Path requested) {
        Path absolute = requested.toAbsolutePath().normalize();
        try {
            return canonicalFuturePath(absolute);
        } catch (IOException unavailable) {
            return absolute;
        }
    }

    public boolean registeredOutputAvailable(Path root) {
        try {
            Path real = plainDirectory(root);
            if (!real.equals(root.toAbsolutePath().normalize()) || !Files.isReadable(real)) {
                return false;
            }
            var marker = readMarker(real);
            return marker != null && marker.rootId().equals(outputRegistration(real));
        } catch (IOException unavailable) {
            return false;
        }
    }

    public synchronized OutputRoot validateOutputRoot(Path batchRoot, Path caseFolder, Path requested) {
        try {
            Path batch = plainDirectory(batchRoot);
            Path source = plainDirectory(caseFolder);
            Path output = canonicalFuturePath(requested.toAbsolutePath().normalize());
            Path reserved = batch.resolve(OUTPUT_DIRECTORY);
            boolean inBatch = output.startsWith(batch);
            if (output.startsWith(source) || source.startsWith(output)
                    || (inBatch && !output.equals(reserved))) {
                throw new IOException("输出位置应与报销原件目录隔离");
            }
            String registered = outputRegistration(output);
            boolean newDirectory = Files.notExists(output, LinkOption.NOFOLLOW_LINKS);
            if (newDirectory) {
                if (registered != null) {
                    throw new IOException("已登记的输出目录不可访问");
                }
                Files.createDirectories(output);
            }
            output = plainDirectory(output);
            var marker = readMarker(output);
            if (registered != null && (marker == null || !registered.equals(marker.rootId()))) {
                throw new IOException("输出目录标记与登记不一致");
            }
            if (marker == null) {
                if (Files.exists(output.resolve(MARKER_FILE), LinkOption.NOFOLLOW_LINKS)
                        || containsFiles(output) || (inBatch && !newDirectory)) {
                    throw new IOException("目录已有资料或标记无效，无法用作输出位置");
                }
                marker = writeMarker(output);
            }
            if (!Files.isWritable(output)) {
                throw new IOException("输出目录不可写");
            }
            registerOutput(output, inBatch ? batch : null, marker);
            return new OutputRoot(output, marker.rootId());
        } catch (IOException error) {
            throw new CaseException("OUTPUT_NOT_WRITABLE", error.getMessage() + " " + requested, error);
        }
    }

    Set<Path> registeredOutputRoots() {
        return database.read(connection -> {
            var roots = new HashSet<Path>();
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT root_real_path FROM output_root")) {
                while (rows.next()) {
                    roots.add(Path.of(rows.getString(1)));
                }
            }
            return Set.copyOf(roots);
        });
    }

    static boolean excluded(Path directory, Path batch, Set<Path> registered) {
        return registered.stream().anyMatch(directory::startsWith)
                || (batch.equals(directory.getParent()) && OUTPUT_DIRECTORY.equals(directory.getFileName().toString())
                        && readMarker(directory) != null);
    }

    static boolean hidden(Path path) throws IOException {
        String name = path.getFileName().toString();
        if (name.startsWith(".") || name.startsWith("~$") || Files.isHidden(path)) {
            return true;
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            var view = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return view != null && view.readAttributes().isSystem();
        }
        return false;
    }

    static Path plainDirectory(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isOther()) {
            throw new IOException("目录不是普通目录 " + input);
        }
        Path real = path.toRealPath();
        Path parent = path.getParent();
        if (parent != null && !parent.toRealPath().equals(real.getParent())) {
            throw new IOException("目录指向其他位置 " + input);
        }
        if (parent != null && !sameNameOnVolume(parent.toRealPath(), path.getFileName(), real.getFileName())) {
            throw new IOException("目录指向其他位置 " + input);
        }
        return real;
    }

    static String relativePath(Path parent, Path child) {
        var parts = new StringBuilder();
        for (Path component : parent.relativize(child)) {
            if (!parts.isEmpty()) {
                parts.append('/');
            }
            parts.append(component);
        }
        return parts.toString();
    }

    private static boolean sameNameOnVolume(Path parent, Path supplied, Path actual) throws IOException {
        return supplied.equals(actual) || Files.isSameFile(parent.resolve(supplied), parent.resolve(actual));
    }

    private static Path canonicalFuturePath(Path candidate) throws IOException {
        if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = candidate.getParent();
            if (parent == null) {
                throw new IOException("目标卷不可访问");
            }
            return canonicalFuturePath(parent).resolve(candidate.getFileName());
        }
        return plainDirectory(candidate);
    }

    private static boolean containsFiles(Path directory) throws IOException {
        try (var children = Files.newDirectoryStream(directory)) {
            return children.iterator().hasNext();
        }
    }

    private static Marker readMarker(Path directory) {
        Path file = directory.resolve(MARKER_FILE);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) {
                return null;
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                var json = JSON.readTree(input);
                if (json == null || !json.isObject() || !APP_ID.equals(json.path("appId").textValue())
                        || !json.path("formatVersion").isIntegralNumber()
                        || !json.path("formatVersion").canConvertToInt()
                        || json.path("formatVersion").intValue() != MARKER_VERSION
                        || !validUuid(json.path("rootId").textValue())) {
                    return null;
                }
                return new Marker(APP_ID, MARKER_VERSION, json.path("rootId").textValue());
            }
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean validUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static Marker writeMarker(Path directory) throws IOException {
        var marker = new Marker(APP_ID, MARKER_VERSION, UUID.randomUUID().toString());
        Files.write(directory.resolve(MARKER_FILE), JSON.writeValueAsBytes(marker),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        return marker;
    }

    private String outputRegistration(Path path) {
        return database.read(connection -> {
            try (var query = connection.prepareStatement("SELECT root_id FROM output_root WHERE root_path_key = ?")) {
                query.setString(1, path.toString());
                try (var rows = query.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
    }

    private void registerOutput(Path path, Path batch, Marker marker) {
        boolean registered = database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO output_root(root_id, batch_real_path, root_real_path, root_path_key, marker_version)
                      VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                statement.setString(1, marker.rootId());
                statement.setString(2, batch == null ? null : batch.toString());
                statement.setString(3, path.toString());
                statement.setString(4, path.toString());
                statement.setInt(5, marker.formatVersion());
                statement.executeUpdate();
            }
            try (var query = connection.prepareStatement("""
                    SELECT 1 FROM output_root WHERE root_id = ? AND root_path_key = ?
                    """)) {
                query.setString(1, marker.rootId());
                query.setString(2, path.toString());
                try (var rows = query.executeQuery()) {
                    return rows.next();
                }
            }
        });
        if (!registered) {
            throw new CaseException("OUTPUT_NOT_WRITABLE", "输出目录的标记已在其他位置登记 " + path);
        }
    }
}
