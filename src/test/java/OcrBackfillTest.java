import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OcrBackfillTest {
    private static final ImageOcr.Mode WINDOWS = ImageOcr.Mode.WINDOWS;
    @TempDir Path root;

    @Test void fillsOnlySavedPicturesAndPersistsEmptyOcr() throws Exception {
        var fixture = fixture(2);
        String before = Files.readString(fixture.note);
        String generation = generation(fixture.note);
        Path originals = assets(fixture.note, generation);
        var entries = PictureAssets.entries(fixture.note, generation);
        var first = entries.getFirst();
        byte[] picture = Files.readAllBytes(originals.resolve(first.filename()));
        Files.delete(fixture.source);
        assertEquals(new OcrBackfill.Status(2, 2), OcrBackfill.inspect(fixture.library, fixture.note));
        assertThrows(IOException.class, () -> OcrBackfill.run(fixture.library, fixture.note,
                new ImageOcr(ImageOcr.Mode.OFF, null)));

        assertEquals(new OcrBackfill.Result(2, 0), OcrBackfill.run(fixture.library, fixture.note, WINDOWS,
                (data, image) -> image.getRGB(0, 0) == 0xFFAA0000 ? "문서찾기 39182\n두 번째 줄" : "  "));
        String updated = generation(fixture.note);
        String ocrBlock = PictureAssets.ocrBlock(first.location(), "문서찾기 39182\n두 번째 줄");
        assertEquals(before, Files.readString(fixture.note).replace(updated, generation).replace(ocrBlock, ""));
        assertArrayEquals(picture, Files.readAllBytes(assets(fixture.note, updated).resolve(first.filename())));
        assertFalse(Files.exists(originals));
        assertEquals(new OcrBackfill.Status(0, 2), OcrBackfill.inspect(fixture.library, fixture.note));
        assertEquals(new OcrBackfill.Result(0, 0), OcrBackfill.run(fixture.library, fixture.note, WINDOWS,
                (data, image) -> fail("빈 결과를 포함해 처리한 그림을 다시 호출하면 안 됩니다.")));
        assertEquals(updated, generation(fixture.note));
        var hit = SearchIndex.search(fixture.library, "문서찾기 39182", SearchIndex.Scope.IMAGE, 10).getFirst();
        assertTrue(Files.isRegularFile(fixture.library.resolve(hit.picture().relativePath())));
        assertNotNull(PictureAssets.preview(hit.md(), hit.picture(), true));
    }

    @Test void resumesFromPersistedEmptyResultAfterFailureAndInterruption() throws Exception {
        var fixture = fixture(3);
        String generation = generation(fixture.note);
        byte[] before = Files.readAllBytes(fixture.note);
        var available = new ArrayList<Integer>();
        assertThrows(IOException.class, () -> OcrBackfill.run(fixture.library, fixture.note, WINDOWS, (data, image) -> {
            int id = image.getRGB(0, 0) - 0xFFAA0000;
            available.add(id);
            if (id > 0) throw new IOException("합성 인식 실패");
            return "";
        }));
        assertEquals(List.of(0, 1), available);
        assertArrayEquals(before, Files.readAllBytes(fixture.note));

        available.clear();
        try {
            assertThrows(InterruptedIOException.class, () -> OcrBackfill.run(fixture.library, fixture.note, WINDOWS,
                    (data, image) -> {
                        available.add(image.getRGB(0, 0) - 0xFFAA0000);
                        Thread.currentThread().interrupt();
                        return "중단 이전 완료된 결과";
                    }));
        } finally {
            Thread.interrupted();
        }
        assertEquals(List.of(1), available);
        assertArrayEquals(before, Files.readAllBytes(fixture.note));
        assertEquals(3, OcrBackfill.pending(fixture.library, fixture.note));

        available.clear();
        assertEquals(new OcrBackfill.Result(3, 0), OcrBackfill.run(fixture.library, fixture.note, WINDOWS, (data, image) -> {
            available.add(image.getRGB(0, 0) - 0xFFAA0000);
            return "마지막 그림 결과";
        }));
        assertEquals(List.of(2), available);
        assertTrue(Files.readString(fixture.note).contains("중단 이전 완료된 결과"));
        assertTrue(Files.readString(fixture.note).contains("마지막 그림 결과"));
        assertFalse(Files.exists(assets(fixture.note, generation)));
    }

    @Test void legacyManifestKeepsExistingTextAndClassifiesUnsupportedPicture() throws Exception {
        var fixture = fixture(3);
        String generation = generation(fixture.note);
        var original = PictureAssets.entries(fixture.note, generation);
        Path assets = assets(fixture.note, generation);
        Path manifest = assets.resolve("images.jsonl");
        var lines = Files.readAllLines(manifest);
        var gson = new Gson();
        for (int i = 0; i < lines.size(); i++) {
            var legacy = JsonParser.parseString(lines.get(i)).getAsJsonObject();
            legacy.remove("state");
            if (i == 0) legacy.addProperty("text", "이전 인식 결과");
            lines.set(i, gson.toJson(legacy));
        }
        Files.write(manifest, lines);
        var entry = original.getFirst();
        var reference = new PictureAssets.Reference(PictureAssets.prefix(PictureAssets.key(fixture.note), generation)
                + entry.filename(), entry.location());
        String anchor = PictureAssets.markdownLine(reference) + "\n" + PictureAssets.caption(reference) + "\n\n";
        Files.writeString(fixture.note, Files.readString(fixture.note).replace(anchor,
                anchor + PictureAssets.ocrBlock(entry.location(), "이전 인식 결과")));
        Files.writeString(assets.resolve(original.get(2).filename()), "해독할 수 없는 합성 바이트");

        assertEquals(new OcrBackfill.Status(2, 3), OcrBackfill.inspect(fixture.library, fixture.note));
        assertEquals(new OcrBackfill.Result(1, 1), OcrBackfill.run(fixture.library, fixture.note, WINDOWS, (data, image) -> {
            assertEquals(0xFFAA0001, image.getRGB(0, 0));
            return "새 인식 결과";
        }));
        var entries = PictureAssets.entries(fixture.note, generation(fixture.note));
        assertEquals(List.of("이전 인식 결과", "새 인식 결과", ""), entries.stream().map(PictureAssets.Entry::text).toList());
        assertEquals(PictureAssets.OcrState.UNSUPPORTED, entries.get(2).state());
        assertEquals(new OcrBackfill.Status(0, 3), OcrBackfill.inspect(fixture.library, fixture.note));
    }

    @Test void ignoresCheckpointFromDifferentEngineAndKeepsConcurrentUpdates() throws Exception {
        var fixture = fixture(2);
        String generation = generation(fixture.note);
        Path manifest = assets(fixture.note, generation).resolve("images.jsonl");
        byte[] before = Files.readAllBytes(fixture.note);
        int[] calls = {0};
        assertThrows(IOException.class, () -> OcrBackfill.run(fixture.library, fixture.note, WINDOWS, (data, image) -> {
            if (++calls[0] == 2) throw new IOException("합성 중단");
            return "이전 임시 결과";
        }));
        FileTime newStamp = FileTime.fromMillis(Files.getLastModifiedTime(manifest).toMillis() + 5000);
        calls[0] = 0;
        assertThrows(IOException.class, () -> OcrBackfill.run(fixture.library, fixture.note, ImageOcr.Mode.PADDLE, (data, image) -> {
            if (++calls[0] == 2) Files.setLastModifiedTime(manifest, newStamp);
            return "다른 엔진 결과";
        }));
        assertEquals(2, calls[0]);
        assertArrayEquals(before, Files.readAllBytes(fixture.note));
        assertEquals(newStamp, Files.getLastModifiedTime(manifest));
        calls[0] = 0;
        assertEquals(new OcrBackfill.Result(2, 0), OcrBackfill.run(fixture.library, fixture.note, WINDOWS, (data, image) -> {
            calls[0]++;
            return "다시 인식한 결과";
        }));
        assertEquals(2, calls[0]);
        assertFalse(Files.readString(fixture.note).contains("이전 임시 결과"));
    }

    @Test void noPicturesHasNothingToProcess() throws Exception {
        var fixture = fixture(0);
        assertEquals(new OcrBackfill.Status(0, 0), OcrBackfill.inspect(fixture.library, fixture.note));
        assertEquals(new OcrBackfill.Result(0, 0), OcrBackfill.run(fixture.library, fixture.note, WINDOWS,
                (data, image) -> fail("보존 그림이 없습니다.")));
    }

    @Test void publishingOnlyUnsupportedStateStillUpdatesTheGeneration() throws Exception {
        var fixture = fixture(1);
        String generation = generation(fixture.note);
        var entry = PictureAssets.entries(fixture.note, generation).getFirst();
        Files.writeString(assets(fixture.note, generation).resolve(entry.filename()), "지원하지 않는 합성 바이트");

        assertEquals(new OcrBackfill.Result(0, 1), OcrBackfill.run(fixture.library, fixture.note, WINDOWS,
                (data, image) -> fail("해독할 수 없는 그림을 엔진에 전달하면 안 됩니다.")));
        assertNotEquals(generation, generation(fixture.note));
        assertEquals(new OcrBackfill.Status(0, 1), OcrBackfill.inspect(fixture.library, fixture.note));
    }

    private static String generation(Path note) throws IOException {
        return Objects.requireNonNull(PictureAssets.currentGeneration(note));
    }

    private static Path assets(Path note, String generation) {
        return note.getParent().resolve(PictureAssets.prefix(PictureAssets.key(note), generation));
    }

    private record Fixture(Path library, Path source, Path note) {}

    private Fixture fixture(int pictures) throws Exception {
        Path source = root.resolve("합성 그림.xlsx");
        Path library = root.resolve("library");
        try (var book = new XSSFWorkbook(); var output = Files.newOutputStream(source)) {
            var sheet = book.createSheet("검증");
            sheet.createRow(0).createCell(0).setCellValue("표 데이터");
            var drawing = sheet.createDrawingPatriarch();
            for (int i = 0; i < pictures; i++) {
                var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, 0xFFAA0000 + i);
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", bytes);
                int id = book.addPicture(bytes.toByteArray(), Workbook.PICTURE_TYPE_PNG);
                drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, i, i, i + 1, i + 1), id);
            }
            book.write(output);
        }
        try (var off = new ImageOcr(ImageOcr.Mode.OFF, null)) {
            return new Fixture(library, source, Library.convert(source, library, off));
        }
    }
}
