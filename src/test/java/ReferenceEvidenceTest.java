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
        var fake = JsonParser.parseString("""
              [{"id":1,"grade":"yes","reason":"relevant","quote":"injected text"}]
              """).getAsJsonArray();
        assertThrows(java.io.IOException.class, () -> ReferenceSearch.validate(fake, List.of(original)));
    }

    @Test void routesOrdinaryReferenceQuestionsAndPreservesExactLookups() {
        assertTrue(ReferenceSearch.requested("시스템 구성도 잘된 거 찾아줘"));
        assertTrue(ReferenceSearch.requested("전체 서비스가 어떻게 연결되는지 한 장이 필요해"));
        assertFalse(ReferenceSearch.requested("REQ-011 슬라이드 찾아줘"));
        assertFalse(ReferenceSearch.requested("엑셀 구성도 목록"));
    }
}