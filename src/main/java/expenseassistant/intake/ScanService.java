package expenseassistant.intake;

import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.casework.CaseService.CaseInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class ScanService {
    public enum Format { PDF, PNG, JPEG }

    public record SourceFile(Path path, String relativePath, String pathKey, String sha256, Format format) {}
    public record PathIssue(String relativePath, String pathKey, String code, boolean subtree) {}
    public record Progress(String caseId, String jobId, String stage, int completed, int total, String relativePath) {}
    public record ScanResult(Ticket ticket, List<SourceFile> files, List<PathIssue> issues) {
        public ScanResult {
            files = List.copyOf(files);
            issues = List.copyOf(issues);
        }
    }

    public static final class Ticket implements AutoCloseable {
        private final CaseJobs.Job job;
        private final CaseInfo caseInfo;

        private Ticket(CaseJobs.Job job, CaseInfo caseInfo) {
            this.job = job;
            this.caseInfo = caseInfo;
        }

        public CaseJobs.Job job() { return job; }
        public CaseInfo caseInfo() { return caseInfo; }
        public long generation() { return caseInfo.scanGeneration() + 1; }

        @Override
        public void close() { job.close(); }
    }

    private record Candidate(Path path, String relativePath, Format format, BasicFileAttributes attributes) {}
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private final CaseService cases;
    private final PathPolicy paths;
    private final CaseJobs jobs;

    public ScanService(CaseService cases, PathPolicy paths, CaseJobs jobs) {
        this.cases = cases;
        this.paths = paths;
        this.jobs = jobs;
    }

    public Ticket begin(String caseId, long expectedRevision) {
        var job = jobs.begin(caseId);
        try {
            return new Ticket(job, cases.loadForMutation(caseId, expectedRevision));
        } catch (RuntimeException failure) {
            job.close();
            throw failure;
        }
    }

    public ScanResult scan(Ticket ticket) {
        return scan(ticket, progress -> {});
    }

    public ScanResult scan(Ticket ticket, Consumer<Progress> progress) {
        ticket.job().checkActive();
        CaseInfo info = ticket.caseInfo();
        Path folder = info.folder();
        var exclusions = paths.registeredOutputRoots();
        var candidates = new ArrayList<Candidate>();
        var issues = new ArrayList<PathIssue>();
        progress.accept(event(ticket, "ENUMERATE", 0, 0, ""));
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    ticket.job().checkActive();
                    try {
                        Path real = PathPolicy.plainDirectory(directory);
                        boolean caseRoot = directory.equals(folder);
                        boolean excluded = PathPolicy.excluded(real, info.batchRoot(), exclusions);
                        if (caseRoot && (excluded || !real.equals(folder) || !info.batchRoot().equals(real.getParent()))) {
                            issues.add(issue(folder, folder, "PATH_UNREADABLE", true));
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (attributes.isOther() || !real.startsWith(folder) || excluded
                                || (!caseRoot && PathPolicy.hidden(directory))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    } catch (IOException unreadable) {
                        issues.add(issue(folder, directory, "PATH_UNREADABLE", true));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    ticket.job().checkActive();
                    if (folder.equals(file)) {
                        issues.add(issue(folder, file, "PATH_UNREADABLE", true));
                        return FileVisitResult.CONTINUE;
                    }
                    Format format = formatOf(file);
                    if (format == null || !attributes.isRegularFile() || attributes.isOther()) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (!PathPolicy.hidden(file)) {
                            candidates.add(new Candidate(file, PathPolicy.relativePath(folder, file), format, attributes));
                        }
                    } catch (IOException unreadable) {
                        issues.add(issue(folder, file, "PATH_UNREADABLE", false));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    ticket.job().checkActive();
                    issues.add(issue(folder, file, "PATH_UNREADABLE", true));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                    ticket.job().checkActive();
                    if (error != null) {
                        issues.add(issue(folder, directory, "PATH_UNREADABLE", true));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new CaseException("INVALID_CASE_FOLDER", "无法读取报销目录 " + folder, failure);
        }
        candidates.sort(Comparator.comparing(Candidate::relativePath));
        var files = new ArrayList<SourceFile>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            try {
                var source = inspect(candidate, folder, ticket);
                if (source == null) {
                    issues.add(issue(folder, candidate.path(), "PAGE_UNRENDERABLE", false));
                } else {
                    files.add(source);
                }
            } catch (IOException unreadable) {
                issues.add(issue(folder, candidate.path(), "PATH_UNREADABLE", false));
            } catch (CaseException changed) {
                issues.add(issue(folder, candidate.path(), changed.code(), false));
            }
            progress.accept(event(ticket, "HASH", index + 1, candidates.size(), candidate.relativePath()));
        }
        ticket.job().checkActive();
        return new ScanResult(ticket, files, issues);
    }

    public boolean cancel(String jobId) {
        return jobs.cancel(jobId);
    }

    private static SourceFile inspect(Candidate file, Path folder, Ticket ticket) throws IOException {
        ticket.job().checkActive();
        Path realParent = PathPolicy.plainDirectory(file.path().getParent());
        if (!realParent.startsWith(folder) || !realParent.equals(file.path().getParent())) {
            throw new CaseException("SOURCE_CHANGED", "凭证原件的路径在扫描中发生变化");
        }
        var digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(file.path(), LinkOption.NOFOLLOW_LINKS), digest)) {
            if (!knownContent(file.format(), input.readNBytes(1024))) {
                return null;
            }
            byte[] block = new byte[64 * 1024];
            while (input.read(block) != -1) {
                ticket.job().checkActive();
            }
        }
        var after = Files.readAttributes(file.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var before = file.attributes();
        if (!after.isRegularFile() || after.isOther() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new CaseException("SOURCE_CHANGED", "凭证原件在扫描中发生变化");
        }
        return new SourceFile(file.path(), file.relativePath(), file.relativePath(),
                HexFormat.of().formatHex(digest.digest()), file.format());
    }

    private static boolean knownContent(Format format, byte[] header) {
        return switch (format) {
            case PDF -> new String(header, StandardCharsets.ISO_8859_1).contains("%PDF-");
            case PNG -> header.length >= PNG_SIGNATURE.length
                    && java.util.Arrays.equals(header, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length);
            case JPEG -> header.length >= 3 && header[0] == (byte) 0xff && header[1] == (byte) 0xd8
                    && header[2] == (byte) 0xff;
        };
    }

    public static Format formatOf(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return null;
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return Format.PDF;
        if (name.endsWith(".png")) return Format.PNG;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return Format.JPEG;
        return null;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PathIssue issue(Path root, Path path, String code, boolean subtree) {
        String relative = PathPolicy.relativePath(root, path);
        return new PathIssue(relative, relative, code, subtree);
    }

    private static Progress event(Ticket ticket, String stage, int completed, int total, String currentPath) {
        return new Progress(ticket.caseInfo().id(), ticket.job().id(), stage, completed, total, currentPath);
    }
}
