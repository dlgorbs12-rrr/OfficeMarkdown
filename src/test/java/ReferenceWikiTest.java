import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(IsolatedSearchIndex.class)
class ReferenceWikiTest {
    @TempDir Path directory;

    @Test void previewResolvesMissingHangulFontAndPreservesExplicitRuns() throws Exception {
        var east=org.apache.poi.common.usermodel.fonts.FontGroup.EAST_ASIAN;
        var latin=org.apache.poi.common.usermodel.fonts.FontGroup.LATIN;
        org.junit.jupiter.api.Assumptions.assumeTrue(new java.awt.Font("Malgun Gothic",0,12).canDisplay('한'));
        try(var deck=new XMLSlideShow()){
            var shape=deck.createSlide().createTextBox();
            var para=shape.addNewTextParagraph();
            var missing=para.addNewTextRun();missing.setText("시스템 구성");missing.setFontFamily("Malgun Gothic",latin);
            var explicit=para.addNewTextRun();explicit.setText("사용자");explicit.setFontFamily("Gulim",east);
            var english=para.addNewTextRun();english.setText("system");
            assertNull(missing.getFontFamily(east));
            ReferenceWiki.resolveHangulFonts(shape);
            assertEquals("Malgun Gothic",missing.getFontFamily(east));
            assertEquals("Gulim",explicit.getFontFamily(east));
            assertNull(english.getFontFamily(east));
        }
    }
    @Test void rendersAndDescribesTheConvertedSlideOrder() throws Exception {
        Path source = directory.resolve("순서 검증.pptx");
        writeDeck(source, true, Color.RED, Color.BLUE);
        Path note = index(source);
        String converted = Files.readString(note);
        ReferenceIndex.prepare();
        var pages = ReferenceIndex.pages(source);
        assertEquals(List.of(1, 2), pages.stream().map(ReferenceIndex.Page::number).toList());
        assertEquals(Color.BLUE.getRGB(), colorOf(ReferenceWiki.preview(pages.get(0))));
        assertEquals(Color.RED.getRGB(), colorOf(ReferenceWiki.preview(pages.get(1))));

        var images = new ArrayList<Integer>();
        var status = new ArrayList<String>();
        assertEquals("장표 설명 준비 완료 · 문서 1개 / 장표 2장", ReferenceWiki.prepare(status::add, "fixture-model", image -> {
            images.add(colorOf(image));
            return images.size() == 1 ? "파란색 직사각형" : "빨간색 직사각형";
        }));
        assertEquals(List.of(Color.BLUE.getRGB(), Color.RED.getRGB()), images);
        assertTrue(status.stream().anyMatch(s -> s.contains("장표 2/2")));
        assertTrue(ReferenceIndex.pendingSources().isEmpty());
        assertEquals(converted, Files.readString(note));
        assertEquals("준비할 장표 설명이 없습니다.", ReferenceWiki.prepare(status::add, "fixture-model", image -> {
            fail("이미 설명을 저장한 장표를 다시 판독했습니다.");
            return "";
        }));
    }

    @Test void replacementDuringCaptioningIsNotPublishedOrRenderedAgain() throws Exception {
        Path source = directory.resolve("교체 검증.pptx");
        writeDeck(source, false, Color.RED, Color.BLUE);
        index(source);
        ReferenceIndex.prepare();
        var oldPage = ReferenceIndex.pages(source).getFirst();
        var images = new ArrayList<Integer>();
        var status = new ArrayList<String>();
        IOException failure = assertThrows(IOException.class, () -> ReferenceWiki.prepare(status::add, "fixture-model", image -> {
            images.add(colorOf(image));
            writeDeck(source, false, Color.GREEN, Color.YELLOW);
            return "교체 전 이미지 설명";
        }));
        assertEquals(List.of(Color.RED.getRGB()), images);
        assertTrue(failure.getMessage().contains("저장 문서 0개 / 장표 0장"));
        assertTrue(status.getLast().contains("실패 문서 1개"));
        assertThrows(IOException.class, () -> ReferenceWiki.preview(oldPage));

        index(source);
        assertEquals("장표 설명 준비 완료 · 문서 1개 / 장표 2장", ReferenceWiki.prepare(status::add, "fixture-model", image -> "교체 후 이미지"));
        assertEquals(Color.GREEN.getRGB(), colorOf(ReferenceWiki.preview(ReferenceIndex.pages(source).getFirst())));
    }

    @Test void interruptionDoesNotSaveAnUnfinishedSourceAndCanBeRetried() throws Exception {
        Path source = directory.resolve("취소 검증.pptx");
        writeDeck(source, false, Color.RED, Color.BLUE);
        index(source);
        try {
            assertThrows(InterruptedException.class, () -> ReferenceWiki.prepare(ignored -> {}, "fixture-model", image -> {
                Thread.currentThread().interrupt();
                return "취소된 설명";
            }));
        } finally {
            Thread.interrupted();
        }
        assertEquals(List.of(source.toRealPath()), ReferenceIndex.pendingSources());
        assertEquals("장표 설명 준비 완료 · 문서 1개 / 장표 2장", ReferenceWiki.prepare(ignored -> {}, "fixture-model", image -> "재시도 설명"));
    }

    @Test void reportsAStoredSourceWhenAnotherDisappears() throws Exception {
        Path first = directory.resolve("a.pptx");
        Path second = directory.resolve("b.pptx");
        writeDeck(first, false, Color.RED);
        writeDeck(second, false, Color.GREEN);
        index(first);
        index(second);
        var progress = new ArrayList<String>();
        IOException failure = assertThrows(IOException.class, () -> ReferenceWiki.prepare(progress::add, "fixture-model", image -> {
            assertEquals(Color.RED.getRGB(), colorOf(image));
            Files.delete(second);
            return "저장할 이미지 설명";
        }));
        assertTrue(failure.getMessage().contains("저장 문서 1개 / 장표 1장"));
        assertTrue(progress.getLast().contains("실패 문서 1개"));
        assertFalse(ReferenceIndex.pendingSources().contains(first.toRealPath()));
    }

    @Test void acceptsOnlyOneBoundedCaptionAndClaudeFinalResult() throws Exception {
        assertEquals("서비스 구성도", ReferenceWiki.caption("```json\n[\"서비스\\n구성도\"]\n```"));
        assertEquals("", ReferenceWiki.caption("[\"\"]"));
        assertThrows(IOException.class, () -> ReferenceWiki.caption("[]"));
        assertThrows(IOException.class, () -> ReferenceWiki.caption("[34]"));
        assertThrows(IOException.class, () -> ReferenceWiki.caption("[\"" + "가".repeat(701) + "\"]"));
        assertEquals("[\"결과\"]", ReferenceWiki.claudeResult("""
                {"type":"system","subtype":"init"}
                {"type":"result","subtype":"success","is_error":false,"result":"[\\"결과\\"]"}
                """));
        assertThrows(IOException.class, () -> ReferenceWiki.claudeResult("""
                {"type":"result","subtype":"error_during_execution","is_error":true}
                """));
    }

    private Path index(Path source) throws Exception {
        Path library = directory.resolve("Markdown");
        Path note = Library.convert(source, library);
        SearchIndex.indexCompleted(library, List.of(note));
        return note;
    }

    private static int colorOf(BufferedImage image) {
        return image.getRGB(image.getWidth() / 4, image.getHeight() / 2);
    }

    private static void writeDeck(Path source, boolean reverse, Color... colors) throws Exception {
        try (var show = new XMLSlideShow(); var output = Files.newOutputStream(source)) {
            show.setPageSize(new Dimension(960, 540));
            for (Color color : colors) {
                var slide = show.createSlide();
                var shape = slide.createAutoShape();
                shape.setShapeType(ShapeType.RECT);
                shape.setAnchor(new Rectangle2D.Double(80, 80, 800, 360));
                shape.setFillColor(color);
                shape.setText("원본 장표 " + slide.getSlideNumber());
            }
            if (reverse) show.setSlideOrder(show.getSlides().getLast(), 0);
            show.write(output);
        }
    }
}
