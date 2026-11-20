import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocumentImportTest {
    @TempDir Path root;

    @Test void recognizesNewFormatsAndIgnoresWordLockFiles() {
        for (String name : List.of("문서.PDF", "문서.DOCX", "문서.doc")) assertTrue(Library.supported(Path.of(name)));
        for (String name : List.of("~$문서.docx", "문서.pdf.tmp", "문서.dotx")) assertFalse(Library.supported(Path.of(name)));
    }

    @Test void pdfKeepsPageNumbersAcrossBlankAndScannedPages() throws Exception {
        Path source = root.resolve("신규 PDF.pdf");
        Path library = root.resolve("library");
        createPdf(source);
        byte[] original = Files.readAllBytes(source);

        Path markdown = Library.convert(source, library);

        assertArrayEquals(original, Files.readAllBytes(source));
        String text = Files.readString(markdown);
        for (int page = 1; page <= 4; page++) assertTrue(text.contains("## 페이지 " + page + "\n"), text);
        var hit = Library.search(library, "Reference 8241", 10).getFirst();
        assertEquals(source.toRealPath(), hit.source());
        assertEquals("페이지 2", hit.section());
        var scans = SearchIndex.search(library, "", SearchIndex.Scope.IMAGE, 10);
        assertEquals(1, scans.size());
        assertEquals("페이지 3", scans.getFirst().section());
        assertNotNull(PictureAssets.preview(markdown, scans.getFirst().picture(), false));
    }

    @Test void scannedPdfUsesSelectedOcrAndPreservesPreviousResultOnFailure() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path source = root.resolve("scan.pdf");
        Path library = root.resolve("library");
        createPdf(source);

        Path markdown;
        try (var ocr = new ImageOcr(ImageOcr.Mode.WINDOWS, ImageOcr.scripts())) {
            markdown = Library.convert(source, library, ocr);
        }

        var hit = SearchIndex.search(library, "7392", SearchIndex.Scope.IMAGE, 10).getFirst();
        assertEquals("페이지 3", hit.section());
        assertTrue(hit.picture().location().contains("스캔"));
        byte[] prior = Files.readAllBytes(markdown);
        try (var failing = new ImageOcr(ImageOcr.Mode.WINDOWS, root.resolve("missing-ocr"))) {
            assertThrows(IOException.class, () -> Library.convert(source, library, failing));
        }
        assertArrayEquals(prior, Files.readAllBytes(markdown));
        assertEquals(hit, SearchIndex.search(library, "7392", SearchIndex.Scope.IMAGE, 10).getFirst());
    }

    @Test void protectedPdfIsRejectedBeforePublication() throws Exception {
        Path source = root.resolve("restricted.pdf");
        Path library = root.resolve("library");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var permission = new AccessPermission();
            permission.setCanExtractContent(false);
            document.protect(new StandardProtectionPolicy("owner", "", permission));
            document.save(source.toFile());
        }

        var failure = assertThrows(IOException.class, () -> Library.convert(source, library));
        assertTrue(failure.getMessage().contains("추출이 제한"));
        try (var files = Files.list(library)) {
            assertEquals(0, files.count());
        }
    }

    @Test void docxPreservesHeadingsTableTextAndEmbeddedPictures() throws Exception {
        Path source = root.resolve("구성 개요.docx");
        Path library = root.resolve("library");
        byte[] png;
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(sampleImage(), "png", output);
            png = output.toByteArray();
        }
        try (var document = new XWPFDocument(); var output = Files.newOutputStream(source)) {
            document.createParagraph().createRun().setText("본문 소개");
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("운영 계획");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("항목");
            table.getRow(0).getCell(1).setText("내용");
            table.getRow(1).getCell(0).setText("장부");
            table.getRow(1).getCell(1).setText("비용|수익");
            XWPFRun image = table.getRow(1).getCell(1).addParagraph().createRun();
            image.addPicture(new ByteArrayInputStream(png), PictureType.PNG, "sample.png",
                    Units.toEMU(200), Units.toEMU(60));
            document.createParagraph().createRun().setText("표 다음 문장");
            document.write(output);
        }
        byte[] original = Files.readAllBytes(source);

        Path markdown = Library.convert(source, library);

        assertArrayEquals(original, Files.readAllBytes(source));
        String text = Files.readString(markdown);
        assertTrue(text.contains("## 본문\n\n본문 소개"), text);
        assertTrue(text.contains("## 운영 계획\n\n| 항목 | 내용 |"), text);
        assertTrue(text.contains("비용\\|수익"), text);
        assertTrue(text.indexOf("비용\\|수익") < text.indexOf("표 다음 문장"), text);
        assertEquals("운영 계획", Library.search(library, "비용|수익", 10).getFirst().section());
        var picture = SearchIndex.search(library, "", SearchIndex.Scope.IMAGE, 10).getFirst();
        assertEquals("운영 계획", picture.section());
        assertArrayEquals(png, Files.readAllBytes(library.resolve(picture.picture().relativePath())));
    }

    private static void createPdf(Path source) throws IOException {
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var text = new PDPage();
            document.addPage(text);
            try (var contents = new PDPageContentStream(document, text)) {
                contents.beginText();
                contents.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                contents.newLineAtOffset(30, 500);
                contents.showText("Reference 8241");
                contents.endText();
            }
            var scan = new PDPage(new PDRectangle(720, 140));
            document.addPage(scan);
            try (var contents = new PDPageContentStream(document, scan)) {
                contents.drawImage(LosslessFactory.createFromImage(document, sampleImage()), 0, 0);
            }
            document.addPage(new PDPage());
            document.save(source.toFile());
        }
    }

    private static BufferedImage sampleImage() {
        var image = new BufferedImage(720, 140, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("Arial", Font.BOLD, 52));
            graphics.drawString("ARCHIVE 7392", 30, 90);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
