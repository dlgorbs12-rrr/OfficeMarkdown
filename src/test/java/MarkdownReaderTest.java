import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownReaderTest {
    @TempDir Path root;
    @Test void onlyConvertedSpreadsheetGridsCanHideEmptyColumns() throws Exception {
        var grid="| 행 | A | B | C |\n| --- | --- | --- | --- |\n| 1 | value | | tail |";
        var sheet=new MarkdownReader.Page("시트 · 시험",1,grid);
        var converted=root.resolve("sample.xlsx-01234567890123456789.md");
        assertTrue(MarkdownReader.html(sheet,null,converted).contains("빈 열 1개 숨김"));
        var longNote=root.resolve("a".repeat(80)+"-01234567890123456789.md");
        Files.writeString(longNote,"# sheet\n\n[원본 파일 열기](<"+root.resolve("a".repeat(80)+".xlsx").toUri()+">)\n");
        assertTrue(MarkdownReader.html(sheet,null,longNote).contains("빈 열 1개 숨김"));
        assertFalse(MarkdownReader.html(sheet,null,root.resolve("manual.md")).contains("숨김"));
        var slide=new MarkdownReader.Page("슬라이드 1",1,grid.replace("B |","보류 |"));
        assertTrue(MarkdownReader.html(slide,null,root.resolve("sample.pptx-01234567890123456789.md")).contains("보류"));
    }

    @Test void combinedRankedExcerptLeadsToTheMatchingChunk() throws Exception {
        var file=root.resolve("nearby.md");
        Files.writeString(file,"# Nearby\n\n[원본 파일 열기](<"+root.resolve("nearby.docx").toUri()+">)\n\n## Topic\nbravo\n"+"earlier\n".repeat(10)+"prefixalpha … detail\nprefixbravo\n"+"filler\n".repeat(110)+"\nalpha … detail\nbravo\n");
        SearchIndex.synchronize(root,ignored->{});
        var hit=SearchIndex.searchCached(root,"alpha bravo",SearchIndex.Scope.CONTENT,SearchIndex.Mode.RELEVANCE,8).getFirst();
        assertTrue(hit.excerpt().contains(" … "));
        var pages=MarkdownReader.read(file);
        var page=pages.get(MarkdownReader.locate(pages,hit));
        assertTrue(page.part()>1,hit.excerpt()+" "+hit.sourceLines());
        assertEquals(page,MarkdownReader.previewPage(file,hit));
    }

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
