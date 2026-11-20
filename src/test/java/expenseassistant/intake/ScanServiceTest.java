package expenseassistant.intake;

import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.store.Database;
import expenseassistant.support.SampleCorpus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static expenseassistant.intake.TypeInference.CaseType.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ScanServiceTest {
    @TempDir
    Path directory;
    private Database database;
    private CaseService cases;
    private PathPolicy paths;
    private ScanService scan;

    @BeforeEach
    void openDatabase() {
        database = Database.open(directory.resolve("store.sqlite"));
        paths = new PathPolicy(database);
        cases = new CaseService(database, paths);
        scan = new ScanService(cases, paths, new CaseJobs());
    }

    @Test
    void formalScannerMatchesCorpusAndIgnoresHiddenFiles() throws IOException {
        Path batch = directory.resolve("样本批次");
        var entries = SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        var expected = entries.stream().collect(Collectors.groupingBy(entry ->
                Path.of(entry.relativePath()).getName(0).toString()));
        assertEquals(5, paths.listCases(batch).size());
        for (Path child : paths.listCases(batch)) {
            var info = cases.openCase(batch, child);
            var group = expected.get(child.getFileName().toString());
            Map<String, String> wanted = group.stream().collect(Collectors.toMap(
                    entry -> entry.relativePath().substring(entry.relativePath().indexOf('/') + 1),
                    SampleCorpus.Entry::sha256));
            try (var ticket = scan.begin(info.id(), info.revision())) {
                var actual = scan.scan(ticket);
                assertTrue(actual.issues().isEmpty());
                assertEquals(wanted, actual.files().stream().collect(Collectors.toMap(
                        ScanService.SourceFile::relativePath, ScanService.SourceFile::sha256)));
                Path hidden = child.resolve(".cache");
                Files.createDirectory(hidden);
                Files.copy(actual.files().getFirst().path(), hidden.resolve("extra.pdf"));
                Files.writeString(child.resolve("~$shadow.PDF"), "%PDF-office lock");
                assertEquals(actual.files(), scan.scan(ticket).files());
            }
        }
    }

    @Test
    void outputMarkersOnlyExcludeRegisteredOrFixedApplicationRoots() throws IOException {
        Path batch = Files.createDirectory(directory.resolve("批次"));
        Path source = Files.createDirectory(batch.resolve("交通费"));
        Path grandson = Files.createDirectory(source.resolve("凭证"));
        Path reserved = Files.createDirectory(batch.resolve(PathPolicy.OUTPUT_DIRECTORY));
        Path unrelated = Files.createDirectory(batch.resolve("另一个资料目录"));
        String marker = """
                {"appId":"expense-assistant","formatVersion":1,"rootId":"5c40f958-680f-45a2-94c1-ad0e6415010c"}
                """;
        Files.writeString(unrelated.resolve(PathPolicy.MARKER_FILE), marker);
        assertEquals(Set.of("交通费", PathPolicy.OUTPUT_DIRECTORY, "另一个资料目录"), childNames(batch));
        assertEquals("OUTPUT_NOT_WRITABLE",
                assertThrows(CaseException.class, () -> paths.validateOutputRoot(batch, source, reserved)).code());
        assertEquals("OUTPUT_NOT_WRITABLE",
                assertThrows(CaseException.class, () -> paths.validateOutputRoot(batch, source, batch)).code());
        assertEquals("OUTPUT_NOT_WRITABLE",
                assertThrows(CaseException.class, () -> paths.validateOutputRoot(batch, source, grandson)).code());
        assertEquals("INVALID_CASE_FOLDER",
                assertThrows(CaseException.class, () -> cases.openCase(batch, grandson)).code());
        assertEquals("INVALID_CASE_FOLDER",
                assertThrows(CaseException.class, () -> cases.openCase(batch, batch)).code());
        Files.writeString(reserved.resolve(PathPolicy.MARKER_FILE), marker);
        assertEquals(Set.of("交通费", "另一个资料目录"), childNames(batch));
        assertEquals("INVALID_CASE_FOLDER",
                assertThrows(CaseException.class, () -> cases.openCase(batch, reserved)).code());
        var registered = paths.validateOutputRoot(batch, source, reserved);
        assertEquals("5c40f958-680f-45a2-94c1-ad0e6415010c", registered.rootId());
        Files.writeString(reserved.resolve(PathPolicy.MARKER_FILE), "{}");
        assertEquals("OUTPUT_NOT_WRITABLE",
                assertThrows(CaseException.class, () -> paths.validateOutputRoot(batch, source, reserved)).code());
        assertEquals(Set.of("交通费", "另一个资料目录"), childNames(batch));

        Path freshBatch = Files.createDirectory(directory.resolve("空输出批次"));
        Path freshSource = Files.createDirectory(freshBatch.resolve("其他费用"));
        Path freshOutput = freshBatch.resolve(PathPolicy.OUTPUT_DIRECTORY);
        assertEquals(paths.validateOutputRoot(freshBatch, freshSource, freshOutput),
                paths.validateOutputRoot(freshBatch, freshSource, freshOutput));
        assertEquals(Set.of("其他费用"), childNames(freshBatch));
    }

    @Test
    void scanCancellationAndStaleRequestsCannotCommit() throws IOException {
        Path batch = Files.createDirectory(directory.resolve("Batch"));
        Path first = Files.createDirectory(batch.resolve("杭州出差-2026.11.03"));
        var draft = cases.openCase(batch, first);
        assertEquals(draft.id(), cases.openCase(batch, first).id());
        assertEquals(TRAVEL, draft.type());
        assertEquals("STALE_REVISION",
                assertThrows(CaseException.class, () -> scan.begin(draft.id(), draft.revision() + 1)).code());
        try (var ticket = scan.begin(draft.id(), draft.revision())) {
            var result = scan.scan(ticket);
            assertTrue(result.files().isEmpty());
            assertEquals("BUSY",
                    assertThrows(CaseException.class, () -> scan.begin(draft.id(), draft.revision())).code());
            assertTrue(scan.cancel(ticket.job().id()));
            assertThrows(CancellationException.class, () -> scan.scan(ticket));
            assertThrows(CancellationException.class, () -> ticket.job().commit(
                    () -> fail("cancelled scan must never call the commit path")));
        }
        assertEquals(draft, cases.load(draft.id()));
        try (var next = scan.begin(draft.id(), draft.revision())) {
            assertTrue(scan.scan(next).issues().isEmpty());
        }
    }

    @Test
    void unreadableSubtreeIsReportedAsAnUncertainScope() throws IOException {
        Path batch = Files.createDirectory(directory.resolve("Batch"));
        Path first = Files.createDirectory(batch.resolve("交通费"));
        Path subtree = Files.createDirectory(first.resolve("不可读"));
        assumeTrue(Files.getFileAttributeView(subtree, PosixFileAttributeView.class) != null);
        Files.writeString(subtree.resolve("票据.pdf"), "%PDF-test");
        var original = Files.getPosixFilePermissions(subtree);
        var draft = cases.openCase(batch, first);
        try {
            Files.setPosixFilePermissions(subtree, Set.of());
            assumeFalse(Files.isReadable(subtree), "effective user can read permissionless directories");
            try (var ticket = scan.begin(draft.id(), draft.revision())) {
                var result = scan.scan(ticket);
                assertTrue(result.files().isEmpty());
                assertEquals(
                        java.util.List.of(new ScanService.PathIssue("不可读", "不可读", "PATH_UNREADABLE", true)),
                        result.issues());
            }
        } finally {
            Files.setPosixFilePermissions(subtree, original);
        }
    }

    @Test
    void symbolicLinksAndMislabeledContentAreNeverRecognized() throws IOException {
        Path batch = Files.createDirectory(directory.resolve("Batch"));
        Path first = Files.createDirectory(batch.resolve("交通费"));
        Path unrelated = Files.createDirectory(directory.resolve("Unrelated"));
        Path outside = Files.writeString(unrelated.resolve("outside.PDF"), "%PDF-outside");
        Files.writeString(first.resolve("fake.jpg"), "%PDF-not-jpeg");
        try {
            Files.createSymbolicLink(batch.resolve("short-cut"), unrelated);
            Files.createSymbolicLink(first.resolve("external.pdf"), outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            assumeTrue(false, "symbolic links unavailable on this test host");
        }
        assertEquals(Set.of("交通费"), childNames(batch));
        assertThrows(CaseException.class, () -> cases.openCase(batch, batch.resolve("short-cut")));
        var draft = cases.openCase(batch, first);
        try (var ticket = scan.begin(draft.id(), 0)) {
            var result = scan.scan(ticket);
            assertTrue(result.files().isEmpty());
            assertEquals("PAGE_UNRENDERABLE", result.issues().getFirst().code());
        }
    }

    @Test
    void typeInferenceSupportsExactAliasesAndCompleteDateShapes() {
        assertEquals(TRAVEL, TypeInference.infer("深圳出差－２０２６．１１．１２～１３"));
        assertEquals(TRAVEL, TypeInference.infer("宁波出差-2026.12.31~2027.01.02"));
        assertEquals(ENTERTAINMENT, TypeInference.infer(" 业务招待 "));
        assertEquals(PRIVATE_CAR, TypeInference.infer("私车公用"));
        assertEquals(TAXI, TypeInference.infer("交通费"));
        assertEquals(OTHER, TypeInference.infer("其他报销"));
        assertNull(TypeInference.infer("深圳出差-2026.11.31"));
        assertNull(TypeInference.infer("深圳出差-2026.11.13~12"));
        assertNull(TypeInference.infer("出差-2026.11.12"));
        assertNull(TypeInference.infer("其他费用测试"));
    }

    @Test
    void travelDirectoryExposesValidatedDestinationAndDateRange() {
        var start = java.time.LocalDate.of(2026, 11, 12);
        assertEquals(new TypeInference.TravelDirectory("深圳", start, start.plusDays(1)),
                TypeInference.travelDirectory(" 深圳 出差－２０２６．１１．１２～１３ ").orElseThrow());
        assertEquals(new TypeInference.TravelDirectory("深圳", start, start),
                TypeInference.travelDirectory("深圳出差-2026.11.12").orElseThrow());
        assertEquals(new TypeInference.TravelDirectory("深圳", start, start.plusMonths(1)),
                TypeInference.travelDirectory("深圳出差-2026.11.12~12.12").orElseThrow());
        assertEquals(java.time.LocalDate.of(2027, 1, 2),
                TypeInference.travelDirectory("宁波出差-2026.12.31~2027.01.02").orElseThrow().endDate());
        assertTrue(TypeInference.travelDirectory("深圳出差-2026.11.31").isEmpty());
        assertTrue(TypeInference.travelDirectory("深圳出差-2026.11.13~12").isEmpty());
        assertTrue(TypeInference.travelDirectory("其他报销").isEmpty());
    }

    private Set<String> childNames(Path batch) {
        return paths.listCases(batch).stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toSet());
    }
}
