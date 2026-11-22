import com.google.gson.JsonParser;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceEvidenceTest {
    @TempDir Path folder;

    @Test void editedMarkdownCannotBecomeOriginalEvidence() throws Exception {
        var source = folder.resolve("example.pptx");
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(source)) {
            deck.createSlide().createTextBox().setText("verified original");
            deck.write(out);
        }
        var note = Library.convert(source, folder.resolve("notes"));
        String markdown = Files.readString(note);
        assertTrue(markdown.contains("verified original"));
        Files.writeString(note, markdown.replace("verified original", "injected text"));
        SearchIndex.indexCompleted(note.getParent(), List.of(note));
        ReferenceIndex.prepare();
        var page = ReferenceIndex.pages(source).getFirst();
        var original = ReferenceOriginal.verified(page, new HashMap<>());
        assertTrue(original.text().contains("verified original"));
        assertFalse(original.text().contains("injected text"));
        assertFalse(original.hit().excerpt().contains("injected text"));
        var fake = JsonParser.parseString("""
              [{"id":1,"grade":"yes","reason":"relevant","quote":"injected text"}]
              """).getAsJsonArray();
        assertThrows(java.io.IOException.class, () -> ReferenceSearch.validate(fake, List.of(original)));
    }

    @Test void unpreparedLegacySourceAffectsOnlyItsVisibleScope() throws Exception {
        var note = folder.resolve("old.md");
        var missing = folder.resolve("missing.pptx");
        Files.writeString(note, "# Old\n\n[원본 파일 열기](<" + missing.toUri() + ">)\n\n## 슬라이드 1\nold\n");
        SearchIndex.indexCompleted(folder, List.of(note));
        ReferenceIndex.prepare();
        assertTrue(ReferenceIndex.searchDetailed("없는장표", new SearchIndex.Focus(note, null)).unavailable());
        assertFalse(ReferenceIndex.searchDetailed("없는장표", new SearchIndex.Focus(folder.resolve("other.md"), null)).unavailable());
    }

    @Test void sourceLostWhileJudgingCannotProduceNormalAbstention() throws Exception {
        var source = folder.resolve("changing.pptx");
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(source)) {
            deck.createSlide().createTextBox().setText("architecture");
            deck.write(out);
        }
        var note = Library.convert(source, folder.resolve("notes"));
        SearchIndex.indexCompleted(note.getParent(), List.of(note));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.io.IOException.class, () -> ReferenceSearch.evaluate("architecture", null, prompt -> {
            if (calls.incrementAndGet() == 1) return "[]";
            Files.delete(source);
            return "[{\"id\":1,\"grade\":\"no\"}]";
        }, status -> {}));
        assertEquals(2, calls.get());
    }

    @Test void routesOrdinaryReferenceQuestionsAndPreservesExactLookups() {
        assertTrue(ReferenceSearch.requested("시스템 구성도 잘된 거 찾아줘"));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("전체 서비스가 어떻게 연결되는지 한 장이 필요해","",null));
        assertFalse(ReferenceSearch.requested("REQ-011 슬라이드 찾아줘"));
        assertFalse(ReferenceSearch.requested("엑셀 구성도 목록"));
    }
}