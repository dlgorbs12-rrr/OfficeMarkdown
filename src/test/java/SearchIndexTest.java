import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SearchIndexTest {
    @TempDir Path root;
    @Test void combineWordsWithinEachDocumentAndSelectedScope() throws Exception {
        String[] titles = {"설계", "가이드", "검토", "AD 구성", "사용 예"};
        String[] bodies = {"ad 연동\n\n## 슬라이드 2\n\n어댑터 설치", "AD 설정", "어댑터 등록", "어댑터 설치", "AD 어댑터 예제"};
        for (int i=0;i<titles.length;i++) {
            Files.writeString(root.resolve(i+".md"), "# "+titles[i]+"\n\n[원본 파일 열기](<"
                    +root.resolve(i+".pptx").toUri().toASCIIString()+">)\n\n## 슬라이드 1\n\n"+bodies[i]);
        }
        var content = SearchIndex.Scope.CONTENT;
        var and = SearchIndex.Mode.AND;
        assertEquals(java.util.Set.of("설계","사용 예"), titles(" ad  어댑터 AD ",content,and));
        var matches = SearchIndex.search(root,"AD 어댑터",content,and,100);
        assertEquals(2,matches.size());
        var evidence=matches.stream().filter(hit -> hit.title().equals("설계")).findFirst().orElseThrow().excerpt();
        for (String fragment : new String[]{"슬라이드 1","ad 연동","슬라이드 2","어댑터 설치"})
            assertTrue(evidence.contains(fragment),evidence);
        assertEquals(java.util.Set.of("설계","사용 예","AD 구성"), titles("어댑터 AD",SearchIndex.Scope.ALL,and));
        assertEquals(java.util.Set.of(titles), titles("AD 어댑터",content,SearchIndex.Mode.OR));
        assertEquals(java.util.Set.of("사용 예"), titles("AD 어댑터",content,SearchIndex.Mode.PHRASE));
        assertTrue(titles("AD 어댑터",SearchIndex.Scope.TITLE,and).isEmpty());
        assertEquals(java.util.Set.of("AD 구성"),titles("AD 어댑터",SearchIndex.Scope.TITLE,SearchIndex.Mode.OR));
        assertTrue(titles("AD 없는단어",content,and).isEmpty());
    }

    private java.util.Set<String> titles(String query, SearchIndex.Scope scope, SearchIndex.Mode mode) throws Exception {
        return SearchIndex.search(root,query,scope,mode,100).stream().map(Library.Hit::title)
                .collect(java.util.stream.Collectors.toSet());
    }
    @Test void existingNotesEditsAndRemovalAreVisibleAcrossConnections() throws Exception {
        Path md = root.resolve("기록.md"), source = root.resolve("sample.xlsx");
        String note = "# 연말 회의록\n\n[원본 파일 열기](<" + source.toUri().toASCIIString()
                + ">)\n\n원본 경로　" + source + "\n\n## 시트 · 서울\n\n프로젝트 \"ALPHA\" 완료\n";
        Files.writeString(md, note);
        assertEquals("연말 회의록", SearchIndex.search(root,"회의록",SearchIndex.Scope.TITLE,10).getFirst().title());
        assertTrue(SearchIndex.search(root,"회의록",SearchIndex.Scope.CONTENT,10).isEmpty());
        assertEquals(source, SearchIndex.search(root,"\"alpha\"",SearchIndex.Scope.CONTENT,10).getFirst().source());
        assertEquals(md, SearchIndex.search(root,"료",SearchIndex.Scope.CONTENT,10).getFirst().md());
        Files.writeString(md,note.replace("완료","진행 중"));
        assertTrue(SearchIndex.search(root,"완료",SearchIndex.Scope.CONTENT,10).isEmpty());
        assertEquals(1,SearchIndex.search(root,"진행 중",SearchIndex.Scope.CONTENT,10).size());
        Files.delete(md);
        assertTrue(SearchIndex.search(root,"",SearchIndex.Scope.ALL,10).isEmpty());
    }
}
