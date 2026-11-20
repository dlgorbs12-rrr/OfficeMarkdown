import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BatchConversionTest {
    @TempDir Path root;

    @Test void duplicateNameToggleUsesExistingMarkdownSourceAndPreservesDifferentExtensions() throws Exception {
        Path library = root.resolve("library");
        Path original = workbook(root.resolve("original/한글 보고서.xlsx"));
        Path duplicate = workbook(root.resolve("other/한글 보고서.XLSX"));
        Path previouslyConverted = Library.convert(original, library);
        byte[] before = Files.readAllBytes(previouslyConverted);
        Files.delete(original);

        var progress = new ArrayList<BatchConversion.Progress>();
        var skipped = BatchConversion.run(List.of(duplicate.getParent()), library, false, ImageOcr.Mode.OFF, 1, progress::add);
        assertEquals(1, skipped.skipped());
        assertEquals(0, skipped.converted());
        assertArrayEquals(before, Files.readAllBytes(previouslyConverted));
        assertEquals(BatchConversion.Phase.SCANNING, progress.getFirst().phase());
        assertEquals(BatchConversion.Phase.CONVERTING, progress.getLast().phase());
        assertEquals(1, progress.getLast().processed());
        assertEquals(1, progress.getLast().total());

        var enabled = BatchConversion.run(List.of(duplicate), library, true, ImageOcr.Mode.OFF, 2, ignored -> {});
        assertEquals(1, enabled.converted());
        assertEquals(0, enabled.skipped());
        assertEquals(1, enabled.indexed());
        assertEquals(0, enabled.indexFailures());
        assertArrayEquals(before, Files.readAllBytes(previouslyConverted));

        Path distinctExtension = root.resolve("한글 보고서.xls");
        try (var book = new org.apache.poi.hssf.usermodel.HSSFWorkbook();
             var out = Files.newOutputStream(distinctExtension)) {
            book.createSheet("현황");
            book.write(out);
        }
        assertEquals(1, BatchConversion.run(List.of(distinctExtension), library, false, ImageOcr.Mode.OFF, 2, ignored -> {}).converted());
    }

    @Test void rowsCompleteAcrossFailuresDuplicatesAndNestedFolder() throws Exception {
        Path bad = Files.createDirectories(root.resolve("broken")).resolve("same.xlsx");
        Files.writeString(bad, "Invalid workbook");
        Path good = workbook(root.resolve("source/nested/SAME.xlsx"));
        Path duplicate = workbook(root.resolve("later/same.xlsx"));
        Files.writeString(good.resolveSibling("ignore.txt"), "ignored");
        var progress = new ArrayList<BatchConversion.Progress>();

        Path different = workbook(root.resolve("other.xlsx"));
        var result = BatchConversion.run(
                List.of(bad, different, root.resolve("source"), duplicate, root.resolve("missing")), root.resolve("library"),
                false, ImageOcr.Mode.OFF, 2, progress::add);
        assertEquals(1, result.failed());
        assertEquals(2, result.converted());
        assertEquals(1, result.skipped());
        assertEquals(1, result.scanFailures());
        assertEquals(2, result.details().size());
        assertEquals(4, progress.getLast().processed());
        assertEquals(4, progress.getLast().total());
        assertEquals(2, progress.getLast().indexed());
        assertTrue(progress.stream().anyMatch(update -> update.phase() == BatchConversion.Phase.INDEXING));
    }

    @Test void folderPreviewControlsExactlyWhichFilesAreConverted() throws Exception {
        Path source = root.resolve("source");
        Path library = root.resolve("library");
        Path oldest = workbook(source.resolve("프로젝트 신규 투입자 매뉴얼_ver0.9.xlsx")).toRealPath();
        Path older = workbook(source.resolve("프로젝트 신규 투입자 매뉴얼_ver1.1.xlsx")).toRealPath();
        Path latest = workbook(source.resolve("프로젝트 신규 투입자 매뉴얼_ver1.10.xlsx")).toRealPath();
        Path nested = workbook(source.resolve("다른 폴더/프로젝트 신규 투입자 매뉴얼_ver0.1.xlsx")).toRealPath();
        var progress = new ArrayList<BatchConversion.Progress>();

        var result = BatchConversion.run(List.of(source, older), library, true, ImageOcr.Mode.OFF, 1, progress::add, preview -> {
            assertFalse(Files.exists(library));
            assertEquals(BatchConversion.Phase.PREVIEW, progress.getLast().phase());
            assertEquals(5, preview.discovered());
            assertEquals(1, preview.latestSkipped());
            assertEquals(4, preview.selected());
            assertEquals(List.of(new LatestVersionSelection.Choice(latest, List.of(oldest))), preview.choices());
            return true;
        });

        assertFalse(result.cancelled());
        assertEquals(1, result.latestSkipped());
        assertEquals(4, result.converted());
        assertEquals(4, progress.getLast().total());
        assertEquals(4, progress.getLast().processed());
        assertTrue(Files.exists(oldest));
        var summaries = SearchIndex.searchCached(library, "", SearchIndex.Scope.ALL, SearchIndex.Mode.PHRASE, 10);
        assertTrue(summaries.stream().anyMatch(note -> note.source().equals(latest)));
        assertTrue(summaries.stream().anyMatch(note -> note.source().equals(older)));
        assertTrue(summaries.stream().anyMatch(note -> note.source().equals(nested)));
        assertFalse(summaries.stream().anyMatch(note -> note.source().equals(oldest)));
    }

    @Test void refusingFolderPreviewLeavesLibraryAndOriginalsUntouched() throws Exception {
        Path old = workbook(root.resolve("source/보고서_v1.xlsx"));
        Path current = workbook(root.resolve("source/보고서_v2.xlsx"));
        Path library = root.resolve("library");
        var progress = new ArrayList<BatchConversion.Progress>();
        byte[] before = Files.readAllBytes(old);

        var result = BatchConversion.run(List.of(old.getParent()), library, true, ImageOcr.Mode.OFF, 1, progress::add,
                preview -> {
                    assertEquals(1, preview.latestSkipped());
                    return false;
                });

        assertTrue(result.cancelled());
        assertEquals(0, result.converted());
        assertEquals(0, result.latestSkipped());
        assertEquals(0, result.indexed());
        assertEquals(BatchConversion.Phase.PREVIEW, progress.getLast().phase());
        assertFalse(Files.exists(library));
        assertArrayEquals(before, Files.readAllBytes(old));
        assertTrue(Files.exists(current));
    }

    private static Path workbook(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(target)) {
            book.createSheet("현황").createRow(0).createCell(0).setCellValue("테스트");
            book.write(out);
        }
        return target;
    }
}
