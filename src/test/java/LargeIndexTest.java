import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LargeIndexTest {
    @TempDir Path library;

    @Test void changedLargeNoteIsHeldAcrossRefreshOcrAndCliUntilAllowed() throws Exception {
        Path note = library.resolve("threshold.md");
        String header = "# 테스트\n\n[원본 파일 열기](<" + library.resolve("test.xlsx").toUri()
                + ">)\n\n## 테스트 시트\n";
        Files.writeString(note, header + "before update\n");
        assertEquals(1, SearchIndex.indexCompleted(library, List.of(note)));

        Files.writeString(note, header + "after update\n");
        // Blank lines keep the test at the real byte threshold without creating a huge FTS database.
        byte[] block = new byte[65536];
        java.util.Arrays.fill(block, (byte)' ');
        block[block.length - 1] = '\n';
        long remaining = SearchIndex.LARGE_BYTES - Files.size(note);
        try (var out = Files.newOutputStream(note, StandardOpenOption.APPEND)) {
            while (remaining > 0) {
                int length = (int)Math.min(remaining, block.length);
                out.write(block, 0, length);
                remaining -= length;
            }
        }
        assertEquals(new SearchIndex.Completion(1, 0), SearchIndex.indexCompletedDetailed(library, List.of(note)));
        Files.writeString(note, "\nlatest change\n", StandardOpenOption.APPEND);
        assertEquals(new SearchIndex.Completion(0, 1), SearchIndex.indexCompletedDetailed(library, List.of(note)));
        assertTrue(SearchIndex.searchCached(library, "after update", SearchIndex.Scope.CONTENT, SearchIndex.Mode.PHRASE, 5).isEmpty());
        assertTrue(SearchIndex.search(library, "latest change", SearchIndex.Scope.CONTENT, 5).isEmpty());
        assertEquals(List.of(note), SearchIndex.largeSettings(library).pending().stream().map(SearchIndex.HeldNote::path).toList());
        SearchIndex.configureLarge(library, true, List.of(note));
        assertEquals(1, SearchIndex.search(library, "latest change", SearchIndex.Scope.CONTENT, 5).size());
        assertTrue(SearchIndex.largeSettings(library).pending().isEmpty());
    }

    @Test void deletingHeldNoteClearsQueueAndLibrarySettingPersists() throws Exception {
        Path note = library.resolve("deleted.md");
        try (var out = new java.io.RandomAccessFile(note.toFile(), "rw")) {
            out.setLength(SearchIndex.LARGE_BYTES + 1);
        }
        SearchIndex.synchronize(library, ignored -> {});
        assertEquals(1, SearchIndex.largeSettings(library).pending().size());
        Files.delete(note);
        Files.createDirectory(note);
        SearchIndex.synchronize(library, ignored -> {});
        SearchIndex.configureLarge(library, false, List.of());
        assertFalse(SearchIndex.largeSettings(library).hold());
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + library.resolve(".office-index-v1.sqlite"));
             var q = db.createStatement(); var rows = q.executeQuery("SELECT count(*) FROM large_pending")) {
            assertEquals(0, rows.getInt(1));
        }
    }
}
