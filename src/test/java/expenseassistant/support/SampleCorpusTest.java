package expenseassistant.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SampleCorpusTest {
    @TempDir Path temporary;

    @Test
    void fixedManifestAndCounts() throws Exception {
        var entries = SampleCorpus.manifest();
        assertEquals(20, entries.size());
        assertEquals(20, entries.stream().map(SampleCorpus.Entry::id).distinct().count());
        assertEquals(20, entries.stream().map(SampleCorpus.Entry::sha256).distinct().count());
        assertEquals(20, entries.stream().map(SampleCorpus.Entry::relativePathSha256).distinct().count());
        assertEquals(Map.of("杭州出差-2026.10.21", 3L, "杭州出差-2026.11.03", 8L,
                        "深圳出差-2026.11.12~13", 5L, "交通费", 2L, "其他报销", 2L),
                entries.stream().collect(Collectors.groupingBy(SampleCorpus.Entry::caseFolder,
                        Collectors.counting())));
    }

    @Test
    void verifyAndCopyLeavesOriginalBatchUntouched() throws Exception {
        var original = SampleCorpus.defaultSource();
        assumeTrue(Files.isDirectory(original), "未提供受控报销样本目录 " + original);
        Path duplicate = temporary.resolve("样本副本");
        var entries = SampleCorpus.verifyAndCopy(original, duplicate);
        assertEquals(entries, SampleCorpus.verify(duplicate));
        Path supplemental = Files.createDirectory(duplicate.resolve("补充材料"));
        Files.copy(duplicate.resolve(entries.getFirst().relativePath()), supplemental.resolve("补充凭证.pdf"));
        assertEquals(entries, SampleCorpus.verify(duplicate));
        Files.delete(duplicate.resolve(entries.getFirst().relativePath()));
        assertThrows(java.io.IOException.class, () -> SampleCorpus.verify(duplicate));
        assertEquals(entries, SampleCorpus.verify(original));
    }
}
