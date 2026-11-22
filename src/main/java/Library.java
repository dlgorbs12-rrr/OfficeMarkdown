import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.net.URI;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture;
import org.apache.poi.sl.usermodel.*;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;

final class Library {
    static boolean supported(Path path) {
        if (path.getFileName() == null) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith("~$") && name.matches(".*\\.(pptx?|xlsx?|docx?|pdf)");
    }

    static Path convert(Path source, Path library) throws Exception {
        return convert(source, library, null);
    }

    static Path convert(Path source, Path library, ImageOcr ocr) throws Exception {
        Path file = source.toRealPath();
        if (!supported(file)) throw new IOException("지원하지 않는 파일 형식");
        Files.createDirectories(library);
        library = library.toRealPath();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(file.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        String name = file.getFileName().toString();
        Path target = library.resolve(name.substring(0, Math.min(80, name.length())) + "-" + hash.substring(0, 20) + ".md");
        for (int attempt=0; ; attempt++) {
            try (var snapshot = EvidenceIndex.supported(file) ? EvidenceIndex.Snapshot.capture(file,library) : null) {
                return convertVersion(file,target,ocr,snapshot);
            } catch (EvidenceIndex.Changed changed) {
                if (attempt != 0) throw changed;
            }
        }
    }

    private static Path convertVersion(Path file, Path target, ImageOcr ocr, EvidenceIndex.Snapshot snapshot) throws Exception {
        Path library = target.getParent();
        String name = file.getFileName().toString();
        String uri = file.toUri().toASCIIString();
        Path input = snapshot == null ? file : snapshot.file();
        Path temp = Files.createTempFile(library, ".convert-", ".tmp");
        try (var pictures = new PictureAssets(target, ocr)) {
            try (BufferedWriter out = Files.newBufferedWriter(temp)) {
                out.write("# " + name + "\n\n[원본 파일 열기](<" + uri + ">)\n\n원본 경로　" + file + "\n");
                pictures.writeHeader(out);
                if (snapshot != null) out.write(EvidenceIndex.hashHeader(snapshot.hash()) + "\n");
                String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (ext.equals("xlsx")) {
                    XlsxStreaming.write(input, library, out, pictures);
                } else if (ext.equals("xls")) {
                    try (Workbook book = WorkbookFactory.create(file.toFile(), null, true)) {
                        spreadsheet(book, out, pictures);
                    }
                } else if (ext.equals("pdf")) {
                    PdfConversion.write(file, out, pictures);
                } else if (ext.equals("docx")) {
                    WordConversion.writeDocx(file, out, pictures);
                } else if (ext.equals("doc")) {
                    WordConversion.writeDoc(file, out, pictures);
                } else {
                    try (InputStream in = Files.newInputStream(input);
                         SlideShow<?, ?> show = ext.equals("ppt") ? new HSLFSlideShow(in) : new XMLSlideShow(in)) {
                        int number = 0;
                        for (Slide<?, ?> slide : show.getSlides()) {
                            out.write("\n## 슬라이드 " + (++number) + "\n\n");
                            writeSlide(slide,out,pictures,"슬라이드 "+number);
                        }
                    }
                }
            }
            if (snapshot != null) snapshot.check(file);
            pictures.publish(temp);
            return target;
        } finally { Files.deleteIfExists(temp); }
    }

    static void writeSlide(Slide<?,?> slide,Writer out,PictureAssets pictures,String section) throws IOException {
        shapes(slide.getShapes(), out, pictures, section);
        Notes<?, ?> notes = slide.getNotes();
        if (notes != null) {
            for (var paragraphs : notes.getTextParagraphs()) {
                for (Object item : paragraphs) {
                    if (!(item instanceof TextParagraph<?, ?, ?> paragraph)) continue;
                    StringBuilder line = new StringBuilder();
                    for (TextRun run : paragraph.getTextRuns()) line.append(run.getRawText());
                    if (!line.toString().isBlank()) out.write("발표 노트 · " + plain(line.toString()) + "\n");
                }
            }
        }
    }

    static String plain(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("(?m)^#", "\\\\#");
    }

    static String sheetSection(String name) {
        return "시트 · " + name.replaceAll("\\R", " ");
    }

    private static void shapes(List<? extends Shape<?, ?>> shapes, Writer out, PictureAssets pictures, String section) throws IOException {
        // PowerPoint stores shapes in stacking order. Read each group in page coordinates.
        var ordered = new ArrayList<>(shapes);
        ordered.sort(Comparator.comparing(Shape::getAnchor, Comparator.nullsLast(
                Comparator.comparingDouble(java.awt.geom.Rectangle2D::getMinY)
                        .thenComparingDouble(java.awt.geom.Rectangle2D::getMinX))));
        for (Shape<?, ?> shape : ordered) {
            if (shape instanceof TableShape<?, ?> table) {
                for (int i = 0; i < table.getNumberOfRows(); i++) {
                    StringJoiner row = new StringJoiner(" | ", "| ", " |\n");
                    for (int j = 0; j < table.getNumberOfColumns(); j++) {
                        TableCell<?, ?> cell = table.getCell(i, j);
                        row.add(cell == null ? "" : tableCell(cell.getText()));
                    }
                    out.write(row.toString());
                    if (i == 0) out.write("|" + " --- |".repeat(table.getNumberOfColumns()) + "\n");
                }
                out.write("\n");
            } else if (shape instanceof XSLFGraphicFrame frame && frame.hasChart() && frame.getChart() != null) {
                ChartText.write(frame.getChart(), out);
            } else if (shape instanceof XSLFPictureShape picture && picture.getXmlObject() instanceof CTPicture image) {
                var props = image.getNvPicPr().getCNvPr();
                for (String field : new LinkedHashSet<>(Arrays.asList(props.getTitle(), props.getDescr()))) {
                    if (field != null && !field.isBlank()) out.write("그림 · " + plain(field) + "\n\n");
                }
                var data = pictures==null ? null : picture.getPictureData();
                if (data != null) pictures.write(data.getData(), data.getType().name(), section, section + " · " + picture.getShapeName(), out);
            } else if (shape instanceof GroupShape<?, ?> group) shapes(group.getShapes(), out, pictures, section);
            else if (shape instanceof TextShape<?, ?> text && !text.getText().isBlank()) {
                var placeholder = text.getTextPlaceholder();
                boolean title = placeholder == TextShape.TextPlaceholder.TITLE
                        || placeholder == TextShape.TextPlaceholder.CENTER_TITLE;
                for (var paragraph : text.getTextParagraphs()) {
                    var line = new StringBuilder();
                    for (TextRun run : paragraph.getTextRuns()) line.append(run.getRawText());
                    String value = line.toString().replace('\u000b', ' ').strip();
                    if (value.isEmpty()) continue;
                    var bullet = paragraph.getBulletStyle();
                    String prefix = title ? "### " : bullet == null ? "" :
                            "  ".repeat(Math.clamp(paragraph.getIndentLevel(), 0, 8)) + "- ";
                    out.write(prefix + plain(value) + (bullet == null ? "\n\n" : "\n"));
                }
                out.write("\n");
            }
        }
    }

    static String tableCell(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|").replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\r\n", "\n")
                .replace('\r', '\n').replace("\n", "<br>");
    }

    private static void sheetPictures(Iterable<org.apache.poi.xssf.usermodel.XSSFShape> shapes,
                                      PictureAssets pictures, String sheet, Writer out) throws IOException {
        for (var shape : shapes) {
            if (shape instanceof org.apache.poi.xssf.usermodel.XSSFPicture picture) {
                var anchor=picture.getClientAnchor();
                String location=anchor==null ? sheet : sheet+" · "+new CellReference(anchor.getRow1(),anchor.getCol1()).formatAsString();
                var data = picture.getPictureData();
                if (data != null) pictures.write(data.getData(), data.suggestFileExtension(), sheetSection(sheet), location, out);
            } else if (shape instanceof org.apache.poi.xssf.usermodel.XSSFShapeGroup group)
                sheetPictures(group,pictures,sheet,out);
        }
    }

    static void spreadsheet(Workbook book, Writer out, PictureAssets pictures) throws IOException {
        DataFormatter display = new DataFormatter(Locale.KOREA);
        display.setUseCachedValuesForFormulaCells(true);
        for (Sheet sheet : book) {
            out.write("\n## " + sheetSection(sheet.getSheetName()) + "\n\n");
            if (sheet instanceof org.apache.poi.xssf.usermodel.XSSFSheet xs && xs.getDrawingPatriarch()!=null)
                sheetPictures(xs.getDrawingPatriarch(),pictures,sheet.getSheetName(),out);
            int first = Integer.MAX_VALUE, last = 0;
            for (Row row : sheet) {
                if (row.getFirstCellNum() >= 0) {
                    first = Math.min(first, row.getFirstCellNum());
                    last = Math.max(last, row.getLastCellNum());
                }
            }
            if (last == 0) continue;
            boolean grid = last - first <= 32;
            if (grid) {
                out.write("| 행 |");
                for (int col = first; col < last; col++) out.write(" " + CellReference.convertNumToColString(col) + " |");
                out.write("\n|" + " --- |".repeat(last - first + 1) + "\n");
            } else out.write("| 셀 주소 | 내용 |\n| --- | --- |\n");
            for (Row row : sheet) {
                String[] cells = grid ? new String[last - first] : null;
                boolean populated = false;
                for (Cell cell : row) {
                    String value = display.formatCellValue(cell);
                    if (cell.getCellType() == CellType.FORMULA) value += " (=" + cell.getCellFormula() + ")";
                    if (!value.isBlank()) {
                        populated = true;
                        String address = cell.getAddress().formatAsString();
                        if (grid) cells[cell.getColumnIndex() - first] = address + " · " + tableCell(value);
                        else out.write("| " + address + " | " + tableCell(value) + " |\n");
                    }
                }
                if (grid && populated) {
                    out.write("| " + (row.getRowNum() + 1) + " |");
                    for (String cell : cells) out.write(" " + Objects.toString(cell, "") + " |");
                    out.write("\n");
                }
            }
        }
    }

    record Hit(Path md, Path source, String title, String section, String excerpt, PictureAssets.Reference picture, String imageDescription,String additionalOcr,List<Path> otherSources,List<String> sourceLines) {
        Hit(Path md,Path source,String title,String section,String excerpt,PictureAssets.Reference picture,String imageDescription,String additionalOcr,List<Path> otherSources){
            this(md,source,title,section,excerpt,picture,imageDescription,additionalOcr,otherSources,List.of());
        }
        Hit(Path md,Path source,String title,String section,String excerpt,PictureAssets.Reference picture,String imageDescription,String additionalOcr){
            this(md,source,title,section,excerpt,picture,imageDescription,additionalOcr,List.of());
        }
        String displayExcerpt(){
            String text=additionalOcr.isEmpty()?excerpt:"검색용 OCR · "+additionalOcr;
            return imageDescription.isEmpty()?text:"그림 설명 · "+imageDescription+"\n"+text;
        }
        Hit(Path md,Path source,String title,String section,String excerpt,PictureAssets.Reference picture){
            this(md,source,title,section,excerpt,picture,"","");
        }
        Hit(Path md, Path source, String title, String section, String excerpt) {
            this(md, source, title, section, excerpt, null);
        }
        @Override public String toString() { return title + "   " + section; }
    }

    static List<Hit> search(Path library, String query, int limit) throws IOException {
        return SearchIndex.search(library, query, SearchIndex.Scope.ALL, limit);
    }
}
