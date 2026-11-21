import java.io.InterruptedIOException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SearchIndexIncrementalTest {
    @TempDir Path library;

    @Test void cachedResultsAreAvailableWhileWriterHasUncommittedChanges() throws Exception {
        var first = note("첫 변환본");
        SearchIndex.search(library, "", SearchIndex.Scope.ALL, 10);
        note("색인 대기");
        var file = SearchIndex.database();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
             var sql = db.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            sql.execute("DELETE FROM docs");
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                var hits = SearchIndex.searchCached(library, "", SearchIndex.Scope.ALL, SearchIndex.Mode.PHRASE, 10);
                assertEquals(List.of(first), hits.stream().map(Library.Hit::md).toList());
            });
            sql.execute("ROLLBACK");
        }
    }

    @Test void cancellationKeepsCommittedDocumentsAndSynchronizationRecoversChangesAndDeletion() throws Exception {
        var notes = new ArrayList<Path>();
        for (int i = 0; i < 100; i++) notes.add(note("변환본 " + i));
        try {
            assertThrows(InterruptedIOException.class, () -> SearchIndex.synchronize(library, progress -> {
                if (progress.updated() > 0) Thread.currentThread().interrupt();
            }));
        } finally {
            Thread.interrupted();
        }
        var partial = SearchIndex.searchCached(library, "", SearchIndex.Scope.ALL, SearchIndex.Mode.PHRASE, 200);
        assertFalse(partial.isEmpty());
        assertTrue(partial.size() < notes.size());
        Files.delete(partial.getFirst().md());
        var additional = note("방금 변환됨");
        assertEquals(1, SearchIndex.indexCompleted(library, List.of(additional)));
        assertEquals(additional, SearchIndex.searchCached(library, "방금 변환됨", SearchIndex.Scope.TITLE,
                SearchIndex.Mode.PHRASE, 2).getFirst().md());
        assertEquals(100, SearchIndex.search(library, "", SearchIndex.Scope.ALL, 200).size());
        assertFalse(SearchIndex.searchCached(library, "", SearchIndex.Scope.ALL, SearchIndex.Mode.PHRASE, 200)
                .stream().anyMatch(hit -> hit.md().equals(partial.getFirst().md())));
    }

    private Path note(String title) throws Exception {
        var source = library.resolve(title + ".xlsx");
        return Files.writeString(library.resolve(title + ".md"), "# " + title + "\n\n[원본 파일 열기](<"
                + source.toUri().toASCIIString() + ">)\n\n## 시트 · 예시\n\n검색할 내용\n");
    }
}
