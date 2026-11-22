import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownReaderTest {
    @TempDir Path root;
    @Test void findAndRenderLateTableRow() throws Exception {
        Path file=root.resolve("자료.md");
        StringBuilder source=new StringBuilder("# 자료\n\n## 시트 · 일정\n\n| 번호 | 확인 |\n| --- | --- |\n");
        for(int i=1;i<=500;i++) source.append("| "+i+" | **검토 "+i+"** |\n");
        Files.writeString(file,source+"\n## 슬라이드 2\n\n- 항목\n");
        assertEquals("시트 · 일정", MarkdownReader.firstPage(file).section());
        var pages=MarkdownReader.read(file);
        var hit=new Library.Hit(file,file,"자료","시트 · 일정","| 495 | **검토 495** |");
        int index=MarkdownReader.locate(pages,hit);
        assertTrue(pages.get(index).part()>1);
        assertEquals(pages.get(index), MarkdownReader.previewPage(file,hit));
        String html=MarkdownReader.html(pages.get(index));
        assertTrue(html.contains("<table ") && html.contains("<strong>검토 495</strong>"),html);
        assertTrue(MarkdownReader.html(pages.getLast()).contains("<li>항목</li>"));
        assertFalse(MarkdownReader.html(new MarkdownReader.Page("x",1,"![x](https://example.com/a.png)")).contains("<img"));
    }
}
