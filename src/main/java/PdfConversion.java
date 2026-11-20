import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

final class PdfConversion extends PDFTextStripper {
    private final Writer markdown;
    private final PictureAssets pictures;
    private final PDFRenderer renderer;
    private final StringWriter pageText = new StringWriter();
    private int writtenPages;

    private PdfConversion(PDDocument document, Writer markdown, PictureAssets pictures) {
        this.markdown = markdown;
        this.pictures = pictures;
        renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(true);
        setSortByPosition(true);
    }

    static void write(Path file, Writer markdown, PictureAssets pictures) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new IOException("텍스트와 이미지 추출이 제한된 PDF입니다.");
            }
            var conversion = new PdfConversion(document, markdown, pictures);
            conversion.writeText(document, conversion.pageText);
            conversion.writeEmptyPages(document.getNumberOfPages());
        } catch (InvalidPasswordException e) {
            throw new IOException("PDF를 열려면 암호가 필요합니다.", e);
        }
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        int number = getCurrentPageNo();
        // PDFTextStripper skips pages without a content stream but still advances their numbers.
        writeEmptyPages(number - 1);
        String section = writePageHeading();
        pageText.getBuffer().setLength(0);
        super.processPage(page);
        String text = pageText.toString().strip();
        if (!text.isEmpty()) {
            markdown.write(Library.plain(text) + "\n\n");
        } else {
            writeScannedPage(page, number, section);
        }
    }

    private void writeEmptyPages(int through) throws IOException {
        while (writtenPages < through) writePageHeading();
    }

    private String writePageHeading() throws IOException {
        String section = "페이지 " + ++writtenPages;
        markdown.write("\n## " + section + "\n\n");
        return section;
    }

    private void writeScannedPage(PDPage page, int number, String section) throws IOException {
        var bounds = page.getCropBox();
        float longestSide = Math.max(bounds.getWidth(), bounds.getHeight());
        if (!(longestSide > 0) || !Float.isFinite(longestSide)) {
            throw new IOException(section + "의 PDF 크기가 올바르지 않습니다.");
        }
        float scale = Math.min(3, 4000 / longestSide);
        var rendered = renderer.renderImage(number - 1, scale);
        var png = new ByteArrayOutputStream();
        ImageIO.write(rendered, "PNG", png);
        pictures.write(png.toByteArray(), "png", section, section + " · 스캔", markdown);
    }
}
