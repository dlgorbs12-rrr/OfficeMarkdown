import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrBase;

final class WordConversion {
    private static final String BODY = "본문";
    private static final Pattern HEADING = Pattern.compile("heading\\s*[1-9]", Pattern.CASE_INSENSITIVE);

    static void writeDocx(Path file, Writer out, PictureAssets pictures) throws IOException {
        try (var contents = OPCPackage.open(file.toFile(), PackageAccess.READ);
             var document = new XWPFDocument(contents)) {
            String section = null;
            for (IBodyElement item : document.getBodyElements()) {
                String text = item instanceof XWPFParagraph paragraph ? paragraph.getText().strip() : "";
                boolean heading = item instanceof XWPFParagraph paragraph && !text.isEmpty() && isHeading(paragraph);
                if (heading) {
                    section = text.replaceAll("\\R", " ");
                    writeHeading(section, out);
                } else if (section == null) {
                    section = BODY;
                    writeHeading(section, out);
                }
                if (item instanceof XWPFParagraph paragraph) {
                    if (!heading) writeParagraph(text, out);
                    writePictures(paragraph, section, pictures, out);
                } else if (item instanceof XWPFTable table) {
                    writeTable(table, out);
                    writeTablePictures(table, section, pictures, out);
                } else if (item instanceof XWPFSDT control) {
                    writeParagraph(control.getContent().getText().strip(), out);
                }
            }
        } catch (InvalidFormatException e) {
            throw new IOException("DOCX 문서 구조를 읽을 수 없습니다.", e);
        }
    }

    static void writeDoc(Path file, Writer out, PictureAssets pictures) throws IOException {
        try (var filesystem = new POIFSFileSystem(file.toFile(), true);
             var document = new WordExtractor(filesystem)) {
            document.setCloseFilesystem(false);
            writeHeading(BODY, out);
            writeParagraph(document.getText().strip(), out);
            for (var picture : document.getDocument().getPicturesTable().getAllPictures()) {
                pictures.write(picture.getContent(), picture.suggestFileExtension(), BODY, BODY + " · 삽입 그림", out);
            }
        }
    }

    private static boolean isHeading(XWPFParagraph paragraph) {
        BigInteger level = outlineLevel(paragraph.getCTPPr());
        if (level != null) return level.signum() >= 0 && level.compareTo(BigInteger.valueOf(9)) < 0;
        String id = paragraph.getStyle();
        if (id == null) return false;
        if (HEADING.matcher(id).matches()) return true;
        var styles = paragraph.getDocument().getStyles();
        var style = styles == null ? null : styles.getStyle(id);
        if (style == null) return false;
        level = outlineLevel(style.getCTStyle().getPPr());
        if (level != null) return level.signum() >= 0 && level.compareTo(BigInteger.valueOf(9)) < 0;
        return HEADING.matcher(Objects.toString(style.getName(), "")).matches();
    }

    private static BigInteger outlineLevel(CTPPrBase properties) {
        return properties == null || !properties.isSetOutlineLvl() ? null : properties.getOutlineLvl().getVal();
    }

    private static void writeHeading(String section, Writer out) throws IOException {
        out.write("\n## " + section + "\n\n");
    }

    private static void writeParagraph(String text, Writer out) throws IOException {
        if (!text.isEmpty()) out.write(Library.plain(text) + "\n\n");
    }

    private static void writeTable(XWPFTable table, Writer out) throws IOException {
        List<XWPFTableRow> rows = table.getRows();
        int columns = rows.stream().mapToInt(row -> row.getTableCells().size()).max().orElse(0);
        if (columns == 0) return;
        for (int index = 0; index < rows.size(); index++) {
            var cells = rows.get(index).getTableCells();
            var line = new StringJoiner(" | ", "| ", " |\n");
            for (int column = 0; column < columns; column++) {
                line.add(column < cells.size() ? Library.tableCell(cells.get(column).getTextRecursively()) : "");
            }
            out.write(line.toString());
            if (index == 0) out.write("|" + " --- |".repeat(columns) + "\n");
        }
        out.write("\n");
    }

    private static void writeTablePictures(XWPFTable table, String section, PictureAssets pictures, Writer out)
            throws IOException {
        for (var row : table.getRows()) {
            for (var cell : row.getTableCells()) {
                for (IBodyElement item : cell.getBodyElements()) {
                    if (item instanceof XWPFParagraph paragraph) writePictures(paragraph, section, pictures, out);
                    else if (item instanceof XWPFTable nested) writeTablePictures(nested, section, pictures, out);
                }
            }
        }
    }

    private static void writePictures(XWPFParagraph paragraph, String section, PictureAssets pictures, Writer out)
            throws IOException {
        for (var run : paragraph.getRuns()) {
            for (var picture : run.getEmbeddedPictures()) {
                var data = picture.getPictureData();
                if (data != null) pictures.write(data.getData(), data.suggestFileExtension(), section,
                        section + " · 삽입 그림", out);
            }
        }
    }
}
