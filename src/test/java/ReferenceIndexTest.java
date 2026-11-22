import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceIndexTest {
    @TempDir Path folder;

    private static void writeDeck(Path source, String... slides) throws IOException {
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(source)) {
            for (var text : slides) {
                var slide = deck.createSlide();
                if (!text.isEmpty()) slide.createTextBox().setText(text);
            }
            deck.write(out);
        }
    }

    private Path converted(Path source) throws Exception {
        Path notes = folder.resolve("converted");
        Path note = Library.convert(source, notes);
        SearchIndex.indexCompleted(notes, List.of(note));
        return note;
    }

    private static List<Integer> numbers(List<ReferenceIndex.Page> pages) {
        return pages.stream().map(ReferenceIndex.Page::number).toList();
    }

    @Test void contextualSearchRanksOnlyTheDisplayedDocuments() throws Exception {
        Path a=folder.resolve("a.pptx"), b=folder.resolve("b.pptx");
        writeDeck(a,"시스템 구성도"); writeDeck(b,"시스템 구성도");
        Path note=converted(a); converted(b);
        ReferenceIndex.prepare();
        assertEquals(2,ReferenceIndex.search("구성도",null).size());
        var scoped=ReferenceIndex.searchDetailed("구성도",null,List.of(note.toString()));
        assertEquals(List.of(a),scoped.pages().stream().map(page->page.hit().source()).toList());
        assertTrue(ReferenceIndex.searchDetailed("구성도",null,List.of()).pages().isEmpty());
        var page=scoped.pages().getFirst();
        var server=new OfficeIndexMcp.Row(note.toString(),a.toString(),"a","슬라이드 1",page.text(),"","",
                page.number(),page.version(),page.sha256());
        var restored=OfficeIndexMcp.JSON.fromJson(OfficeIndexMcp.JSON.toJson(server),OfficeIndexMcp.Row.class);
        assertEquals(page,restored.currentPage(page.hit()));
        assertEquals(2,ReferenceWiki.plan().stream().mapToInt(ReferenceWiki.Target::slides).sum());
        Files.delete(a);
        assertThrows(IOException.class,()->restored.currentPage(page.hit()));
    }

    @Test void searchesTruePartialWordsAndScopesEachPage() throws Exception {
        Path source = folder.resolve("slides.pptx");
        writeDeck(source, "자동권한등록절차", "등록 록절", "", "격리망");
        Path note = converted(source);
        assertTrue(ReferenceIndex.search("등록절", null).isEmpty());
        ReferenceIndex.prepare();
        assertEquals(List.of(1, 2, 3, 4), numbers(ReferenceIndex.pages(source)));
        assertEquals(List.of(1), numbers(ReferenceIndex.search("등록절 장표 찾아줘", null)));
        assertEquals(List.of(4), numbers(ReferenceIndex.search("리망", null)));
        assertTrue(ReferenceIndex.search("리망", new SearchIndex.Focus(note, "슬라이드 1")).isEmpty());
        assertEquals(List.of(4), numbers(ReferenceIndex.search("리망", new SearchIndex.Focus(note, "슬라이드 4"))));
        assertTrue(ReferenceIndex.search("리망", new SearchIndex.Focus(folder.resolve("other.md"), null)).isEmpty());
    }

    @Test void oldLibraryUsesLiveOriginalInsteadOfUnverifiedMarkdown() throws Exception {
        Path source = folder.resolve("legacy.pptx");
        writeDeck(source, "원본장표판단", "승인 흐름");
        Path note = folder.resolve("old.md");
        String old = "# Old\n\n[원본 파일 열기](<" + source.toUri() + ">)\n\n## 슬라이드 9\n이전 문구\n";
        Files.writeString(note, old);
        SearchIndex.indexCompleted(folder, List.of(note));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + SearchIndex.database());
             var sql = db.createStatement()) {
            sql.execute("DROP TABLE evidence_edge");
            sql.execute("DROP TABLE evidence_version");
            sql.execute("DROP TABLE large_pending");
            sql.execute("DROP TABLE pictures");
        }
        ReferenceIndex.prepare();
        assertEquals(old, Files.readString(note));
        var hit = ReferenceIndex.search("장표판단", null).getFirst();
        assertEquals(1, hit.number());
        assertTrue(hit.text().contains("원본장표판단"));
        assertTrue(ReferenceIndex.current(hit));
        assertTrue(ReferenceIndex.search("이전 문구", null).isEmpty());
        assertEquals(List.of(source), ReferenceIndex.pendingSources());
        assertTrue(ReferenceIndex.storeDescriptions(source, hit.sha256(), Map.of(1, "", 2, "클러스터링")));
        assertEquals(List.of(2), numbers(ReferenceIndex.search("러스터", null)));
    }

    @Test void descriptionsAreAtomicAndRankIndependently() throws Exception {
        Path source = folder.resolve("wiki.pptx");
        writeDeck(source, "구성도 구성도 구성도 구성도", "구성도", "네트워크 경계");
        Path note = converted(source);
        ReferenceIndex.prepare();
        var pages = ReferenceIndex.pages(source);
        String hash = pages.getFirst().sha256();
        assertEquals(List.of(source), ReferenceIndex.pendingSources());
        assertEquals(List.of(1, 2), numbers(ReferenceIndex.search("구성도", null)));
        assertFalse(ReferenceIndex.storeDescriptions(source, hash, Map.of(1, "부분 게시")));
        assertTrue(ReferenceIndex.search("게시", null).isEmpty());
        assertTrue(ReferenceIndex.storeDescriptions(source, hash, Map.of(1, "", 2, "구성도", 3, "시스템구성도 VLAN"),
                "model", "1"));
        assertEquals(List.of(2, 1, 3), numbers(ReferenceIndex.search("구성도", null)));
        assertTrue(ReferenceIndex.pendingSources().isEmpty());
        assertEquals(List.of(3), numbers(ReferenceIndex.search("VLAN", null)));
        assertFalse(ReferenceIndex.search("VLAN", null).getFirst().text().contains("VLAN"));
        assertTrue(ReferenceIndex.search("VLAN", new SearchIndex.Focus(note, "슬라이드 2")).isEmpty());
        assertTrue(ReferenceIndex.storeDescriptions(source, hash, Map.of(1, "", 2, "", 3, "")));
        assertTrue(ReferenceIndex.search("VLAN", null).isEmpty());
    }

    @Test void hiddenReplacedAndDeletedPagesCannotSupplyOldEvidence() throws Exception {
        Path source = folder.resolve("live.pptx");
        writeDeck(source, "변경전");
        Path note = converted(source);
        ReferenceIndex.prepare();
        var old = ReferenceIndex.search("변경전", null).getFirst();
        assertTrue(ReferenceIndex.storeDescriptions(source, old.sha256(), Map.of(1, "오래된해설")));

        hide(note, true);
        assertTrue(ReferenceIndex.search("오래된해설", null).isEmpty());
        assertFalse(ReferenceIndex.searchDetailed("오래된해설", null).unavailable());
        assertFalse(ReferenceIndex.current(old));
        assertFalse(ReferenceIndex.storeDescriptions(source, old.sha256(), Map.of(1, "숨김")));
        assertTrue(ReferenceIndex.pendingSources().isEmpty());
        hide(note, false);
        assertTrue(ReferenceIndex.current(old));

        var attributes = Files.getLastModifiedTime(source);
        var bytes = Files.readAllBytes(source);
        bytes[bytes.length - 1] ^= 1;
        Files.write(source, bytes);
        Files.setLastModifiedTime(source, attributes);
        var inaccessible = ReferenceIndex.searchDetailed("변경전", null);
        assertTrue(inaccessible.pages().isEmpty());
        assertTrue(inaccessible.unavailable());
        assertFalse(ReferenceIndex.searchDetailed("존재하지않는검색어", null).unavailable());
        assertTrue(ReferenceIndex.search("오래된해설", null).isEmpty());
        assertFalse(ReferenceIndex.current(old));
        assertFalse(ReferenceIndex.storeDescriptions(source, old.sha256(), Map.of(1, "잘못된 해설")));

        writeDeck(source, "변경후");
        assertEquals(note, converted(source));
        var fresh = ReferenceIndex.search("변경후", null).getFirst();
        assertNotEquals(old.version(), fresh.version());
        assertTrue(ReferenceIndex.current(fresh));
        assertTrue(ReferenceIndex.search("오래된해설", null).isEmpty());
        assertEquals(List.of(source), ReferenceIndex.pendingSources());
        Files.delete(note);
        assertFalse(ReferenceIndex.current(fresh));
        SearchIndex.synchronize(note.getParent(), ignored -> {});
        assertTrue(ReferenceIndex.search("변경후", null).isEmpty());
    }

    @Test void limitsDistinctPagesAndKeepsSameNamedSourcesSeparate() throws Exception {
        Path a = Files.createDirectory(folder.resolve("a")).resolve("same.pptx");
        Path b = Files.createDirectory(folder.resolve("b")).resolve("same.pptx");
        String[] slides = new String[24];
        Arrays.fill(slides, "검색머리말");
        writeDeck(a, slides);
        writeDeck(b, slides);
        Path note = converted(a);
        Path second = converted(b);
        Path copy = Files.createDirectory(folder.resolve("other-notes")).resolve(note.getFileName());
        Files.copy(note, copy);
        SearchIndex.indexCompleted(copy.getParent(), List.of(copy));
        ReferenceIndex.prepare();
        var expected = new ArrayList<Integer>();
        for (int i = 1; i <= 20; i++) expected.add(i);
        assertEquals(expected, numbers(ReferenceIndex.search("머리말", null)));
        var focused = ReferenceIndex.search("머리말", new SearchIndex.Focus(second, null));
        assertEquals(expected, numbers(focused));
        assertTrue(focused.stream().allMatch(page -> page.hit().source().equals(b)));
        assertEquals(24, ReferenceIndex.pages(a).size());
    }

    @Test void candidatesContinuePastDenseDuplicateCopies() throws Exception {
        Path a = folder.resolve("a.pptx");
        Path b = folder.resolve("b.pptx");
        writeDeck(a, "통합검색");
        writeDeck(b, "통합검색");
        Path note = converted(a);
        converted(b);
        Path copies = Files.createDirectory(folder.resolve("copies"));
        var paths = new ArrayList<Path>();
        for (int i = 0; i < 100; i++) paths.add(Files.copy(note, copies.resolve(i + ".md")));
        SearchIndex.indexCompleted(copies, paths);
        ReferenceIndex.prepare();
        assertEquals(List.of(a, b), ReferenceIndex.search("통합검색", null).stream()
                .map(page -> page.hit().source()).toList());
    }

    private static void hide(Path note, boolean hidden) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + SearchIndex.database());
             var stmt = db.prepareStatement(hidden ? "INSERT INTO large_pending(md,size) VALUES(?,120000000)"
                     : "DELETE FROM large_pending WHERE md=?")) {
            stmt.setString(1, note.toString());
            stmt.executeUpdate();
        }
    }
}
