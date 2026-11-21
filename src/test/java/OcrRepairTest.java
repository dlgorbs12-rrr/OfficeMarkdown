import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OcrRepairTest {
    @TempDir Path root;

    @Test void selectedOcrBlocksAndIndexMoveTogether() throws Exception {
        var fixture = fixture("깨짐 � 7392", "ㅁㅜㄴ 8241", "정상 문장 9072");
        var hits = OcrRepair.find(fixture.library, 10);
        assertEquals(2, hits.size());
        var otherOutput = Files.createDirectory(root.resolve("other-output"));
        var remotePicture = SearchIndex.search(otherOutput, "7392", SearchIndex.Scope.IMAGE, 10).getFirst();
        assertEquals(fixture.note, remotePicture.md());
        assertNotNull(PictureAssets.preview(remotePicture.md(), remotePicture.picture(), true));
        assertEquals(fixture.note, SearchIndex.agentCandidates(otherOutput, List.of("7392"), true).getFirst().md());
        assertEquals(1, OcrRepair.find(fixture.library, 1).size());
        Path oldGeneration = picturePath(fixture.library, hits.getFirst()).getParent();
        byte[] input = Files.readAllBytes(fixture.source);
        int[] calls = {0};

        assertEquals(2, OcrRepair.repair(fixture.library, hits, (data, image) -> {
            calls[0]++;
            return image.getRGB(0, 0) == 0xFFAA0000 ? "문서 검색 7392" : "정밀 인식 8241";
        }));
        assertEquals(2, calls[0]);
        assertFalse(Files.isDirectory(oldGeneration));
        assertArrayEquals(input, Files.readAllBytes(fixture.source));
        assertTrue(OcrRepair.find(fixture.library, 10).isEmpty());
        assertTrue(SearchIndex.search(fixture.library, "깨짐", SearchIndex.Scope.ALL, 10).isEmpty());
        for (String expected : List.of("문서 검색 7392", "정밀 인식 8241", "정상 문장 9072")) {
            var hit = SearchIndex.search(fixture.library, expected, SearchIndex.Scope.IMAGE, 10).getFirst();
            assertTrue(Files.isRegularFile(picturePath(fixture.library, hit)));
            assertNotNull(PictureAssets.preview(hit.md(), hit.picture(), true));
            assertTrue(Files.readString(fixture.note).contains(expected));
        }
        assertTrue(Files.readString(fixture.note).contains("업무 본문"));
        assertThrows(IOException.class, () -> OcrRepair.repair(fixture.library, hits,
                (data, image) -> fail("오래된 항목을 다시 인식하면 안 됩니다.")));
    }

    @Test void unusableRecognitionAndConcurrentWritePreserveExistingOutput() throws Exception {
        var fixture = fixture("깨짐 �");
        var hits = OcrRepair.find(fixture.library, 10);
        String original = Files.readString(fixture.note);
        assertEquals(0, OcrRepair.repair(fixture.library, hits, (data, image) -> ""));
        assertEquals(original, Files.readString(fixture.note));
        assertThrows(IOException.class, () -> OcrRepair.repair(fixture.library, hits, (data, image) -> {
            Files.writeString(fixture.note, "외부에서 추가한 문장\n", StandardOpenOption.APPEND);
            return "다시 인식한 문장";
        }));
        assertEquals(original + "외부에서 추가한 문장\n", Files.readString(fixture.note));
        assertTrue(Files.isRegularFile(picturePath(fixture.library, hits.getFirst())));
        try (var generations = Files.list(picturePath(fixture.library, hits.getFirst()).getParent().getParent())) {
            assertEquals(1, generations.count());
        }
    }

    @Test void preservesExistingLineEndingsAndFinalBodyText() throws Exception {
        var fixture = fixture("깨짐 �");
        String original = Files.readString(fixture.note).replace("\n", "\r\n") + "마지막 업무 문장";
        Files.writeString(fixture.note, original);
        String generation = original.lines().map(PictureAssets::generation).filter(Objects::nonNull)
                .findFirst().orElseThrow();

        assertEquals(1, OcrRepair.repair(fixture.library, OcrRepair.find(fixture.library, 10),
                (data, image) -> "새로 인식한 문장"));
        String updated = Files.readString(fixture.note);
        String updatedGeneration = updated.lines().map(PictureAssets::generation).filter(Objects::nonNull)
                .findFirst().orElseThrow();
        assertEquals(original.replace(generation, updatedGeneration).replace("깨짐 �", "새로 인식한 문장"), updated);
    }

    @Test void wholeDocumentSelectionIncludesPreviouslyRecognizedPictures()throws Exception{
        var fixture=fixture("기존 한글","깨짐 �");
        var hits=OcrRepair.documentPictures(fixture.note);
        assertEquals(2,hits.size());
        assertEquals(2,OcrRepair.repair(fixture.library,hits,(data,image)->"정밀 한글"));
        assertTrue(OcrRepair.lowQuality("","페이지 1 · 스캔"));
        assertFalse(OcrRepair.lowQuality("","슬라이드 1 · 로고"));
        assertTrue(OcrRepair.lowQuality("계 정 권 한 신 청 절 차","슬라이드 1"));
        var page=MarkdownReader.read(fixture.note).getLast();
        assertTrue(MarkdownReader.html(page,null,fixture.note).contains("-preview.png"));
        assertFalse(MarkdownReader.html(new MarkdownReader.Page("본문",1,"![임의](file:///C:/random.png)"),
                null,fixture.note).contains("<img"));
    }

    @Test void searchEnrichmentReusesOnlyIdenticalPicturesAndSurvivesAReindex()throws Exception{
        var fixture=fixture("새 메일주소 확인");
        Path second=root.resolve("other");
        Path note=Library.convert(fixture.source,second);
        String before=Files.readString(note);
        SearchIndex.synchronize(fixture.library,p->{});
        SearchIndex.synchronize(second,p->{});
        assertEquals(1,PictureSearchIndex.enrich(null,s->{}).reused());
        assertEquals(0,PictureSearchIndex.enrich(null,s->{}).reused());
        assertEquals(before,Files.readString(note));
        var focus=new SearchIndex.Focus(note,null);
        var hits=SearchIndex.readIndex(db->RelevanceSearch.pictures(db,"메일 주소 확인",10,focus));
        assertEquals(1,hits.size());
        assertTrue(hits.getFirst().excerpt().isEmpty());
        assertEquals("새 메일주소 확인",hits.getFirst().additionalOcr());
        assertEquals(1,SearchIndex.searchCached(second,"메일 주소 확인",SearchIndex.Scope.IMAGE,SearchIndex.Mode.RELEVANCE,10).size());
        assertTrue(DocumentAgent.locate(second,"확인 화면 찾아줘",true,p->"[\"메일 주소\",\"확인\"]",s->{},focus)
                .getFirst().evidence().stream().anyMatch(e->e.quote().contains("메일주소")));
        Library.convert(fixture.source,second);
        SearchIndex.synchronize(second,p->{});
        assertTrue(SearchIndex.readIndex(db->RelevanceSearch.pictures(db,"메일 주소",10,focus)).isEmpty());
    }

    private Path picturePath(Path library, Library.Hit hit) {
        return library.resolve(hit.picture().relativePath());
    }

    private record Fixture(Path source, Path library, Path note) {}

    private Fixture fixture(String... ocrTexts) throws Exception {
        Path source = root.resolve("테스트.xlsx");
        Path library = root.resolve("library");
        try (var book = new XSSFWorkbook(); var output = Files.newOutputStream(source)) {
            var sheet = book.createSheet("검증");
            sheet.createRow(0).createCell(0).setCellValue("업무 본문");
            var drawing = sheet.createDrawingPatriarch();
            for (int i = 0; i < ocrTexts.length; i++) {
                var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, 0xFFAA0000 + i);
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", bytes);
                int imageId = book.addPicture(bytes.toByteArray(), Workbook.PICTURE_TYPE_PNG);
                drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, i, i, i + 1, i + 1), imageId);
            }
            book.write(output);
        }
        Path note = Library.convert(source, library);
        String content = Files.readString(note);
        String generation = content.lines().map(PictureAssets::generation).filter(Objects::nonNull)
                .findFirst().orElseThrow();
        var entries = PictureAssets.entries(note, generation);
        String prefix = PictureAssets.prefix(PictureAssets.key(note), generation);
        StringBuilder manifest = new StringBuilder();
        var gson = new Gson();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var ref = new PictureAssets.Reference(prefix + entry.filename(), entry.location());
            String blockStart = PictureAssets.markdownLine(ref) + "\n그림 " + ref.number() + " · " + entry.location() + "\n\n";
            content = content.replace(blockStart, blockStart + PictureAssets.ocrBlock(entry.location(), ocrTexts[i]));
            manifest.append(gson.toJson(new PictureAssets.Entry(entry.filename(), entry.section(),
                    entry.location(), ocrTexts[i]))).append('\n');
        }
        Files.writeString(note, content);
        Files.writeString(library.resolve(prefix).resolve("images.jsonl"), manifest);
        return new Fixture(source, library, note);
    }
}
