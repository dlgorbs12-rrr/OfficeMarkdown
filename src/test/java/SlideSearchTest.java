import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlideSearchTest {
    @TempDir Path library;

    private Path write(String note, Path source, String body) throws Exception {
        Path md = library.resolve(note + ".md");
        Files.writeString(md, "# 발표\n\n[원본 파일 열기](<" + source.toUri() + ">)\n\n" + body);
        SearchIndex.indexCompleted(library, List.of(md));
        return md;
    }

    private List<Library.Hit> results(String query) throws Exception {
        return SearchIndex.searchCached(library,query,SearchIndex.Scope.ALL,SearchIndex.Mode.RELEVANCE,10);
    }

    @Test void optInBackfillsAndFollowsSourceLifecycle() throws Exception {
        String previous = System.getProperty(SlideSearch.ENABLED);
        try {
            System.clearProperty(SlideSearch.ENABLED);
            String body = "## 슬라이드 1\n6. 수행 일정\n월간 회의\n## 슬라이드 2\n예산 검토\n";
            Path source = library.resolve("one.pptx");
            Path first = write("first",source,body);
            var baseline = results("스케줄표를 보여주는 장표 찾아줘");
            SlideSearch.prepare(SearchIndex.database());
            SlideSearch.prepare(SearchIndex.database());
            assertEquals(baseline,results("스케줄표를 보여주는 장표 찾아줘"));
            System.setProperty(SlideSearch.ENABLED,"true");
            write("copy",source,body);
            write("other",library.resolve("other.xlsx"),body);
            var hit = results("스케줄표를 보여주는 장표 찾아줘");
            assertEquals(1,hit.size());
            assertEquals("슬라이드 1",hit.getFirst().section());
            assertTrue(hit.getFirst().excerpt().contains("수행 일정"));
            assertTrue(results("월간 예산 장표 찾아줘").isEmpty());
            assertTrue(results("양자암호 일정 장표 찾아줘").isEmpty());
            write("first",source,"## 슬라이드 1\n권한 검토\n");
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + SearchIndex.database());
                 var pending = db.prepareStatement("INSERT INTO large_pending(md,size) VALUES(?,?)")) {
                pending.setString(1,library.resolve("copy.md").toString());
                pending.setLong(2,120_000_000);
                pending.executeUpdate();
            }
            assertTrue(results("스케줄표 장표 찾아줘").isEmpty());
            assertEquals(1,results("권한 장표 찾아줘").size());
            Files.delete(first);
            SearchIndex.synchronizeAll(library, ignored -> {});
            assertTrue(results("권한 장표 찾아줘").isEmpty());
        } finally {
            if (previous == null) System.clearProperty(SlideSearch.ENABLED);
            else System.setProperty(SlideSearch.ENABLED,previous);
        }
    }
}
