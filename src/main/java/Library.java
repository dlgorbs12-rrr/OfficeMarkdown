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
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith("~$") && name.matches(".*\\.(pptx?|xlsx?)");
    }

    static Path convert(Path source, Path library) throws Exception {
        return convert(source, library, null);
    }

    static Path convert(Path source, Path library, ImageOcr ocr) throws Exception {
        Path file = source.toRealPath();
        if (!supported(file)) throw new IOException("지원하지 않는 파일 형식");
        Files.createDirectories(library);
        String uri = file.toUri().toASCIIString();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(file.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        String name = file.getFileName().toString();
        Path target = library.resolve(name.substring(0, Math.min(80, name.length())) + "-" + hash.substring(0, 20) + ".md");
        Path temp = Files.createTempFile(library, ".convert-", ".tmp");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temp)) {
                out.write("# " + name + "\n\n[원본 파일 열기](<" + uri + ">)\n\n원본 경로　" + file + "\n");
                String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (ext.startsWith("xls")) {
                    try (Workbook book = WorkbookFactory.create(file.toFile(), null, true)) {
                        spreadsheet(book, out, ocr);
                    }
                } else {
                    try (InputStream in = Files.newInputStream(file);
                         SlideShow<?, ?> show = ext.equals("ppt") ? new HSLFSlideShow(in) : new XMLSlideShow(in)) {
                        int number = 0;
                        for (Slide<?, ?> slide : show.getSlides()) {
                            out.write("\n## 슬라이드 " + (++number) + "\n\n");
                            shapes(slide.getShapes(), out, ocr, "슬라이드 "+number);
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
                    }
                }
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            return target;
        } finally { Files.deleteIfExists(temp); }
    }

    static String plain(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("(?m)^#", "\\\\#");
    }

    private static void shapes(List<? extends Shape<?, ?>> shapes, Writer out, ImageOcr ocr, String location) throws IOException {
        for (Shape<?, ?> shape : shapes) {
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
                if (ocr!=null && picture.getPictureData()!=null)
                    ocr.write(picture.getPictureData().getData(),picture.getPictureData().getType().name(),location+" · "+picture.getShapeName(),out);
            } else if (shape instanceof GroupShape<?, ?> group) shapes(group.getShapes(), out, ocr, location);
            else if (shape instanceof TextShape<?, ?> text && !text.getText().isBlank()) {
                out.write(plain(text.getText()) + "\n\n");
            }
        }
    }

    static String tableCell(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|").replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\r\n", "\n")
                .replace('\r', '\n').replace("\n", "<br>");
    }

    private static void sheetPictures(Iterable<org.apache.poi.xssf.usermodel.XSSFShape> shapes,
                                      ImageOcr ocr, String sheet, Writer out) throws IOException {
        for (var shape : shapes) {
            if (shape instanceof org.apache.poi.xssf.usermodel.XSSFPicture picture) {
                var anchor=picture.getClientAnchor();
                String location=anchor==null ? sheet : sheet+" · "+new CellReference(anchor.getRow1(),anchor.getCol1()).formatAsString();
                if (picture.getPictureData()!=null) ocr.write(picture.getPictureData().getData(),picture.getPictureData().suggestFileExtension(),location,out);
            } else if (shape instanceof org.apache.poi.xssf.usermodel.XSSFShapeGroup group)
                sheetPictures(group,ocr,sheet,out);
        }
    }

    private static void spreadsheet(Workbook book, Writer out, ImageOcr ocr) throws IOException {
        DataFormatter display = new DataFormatter(Locale.KOREA);
        display.setUseCachedValuesForFormulaCells(true);
        for (Sheet sheet : book) {
            out.write("\n## 시트 · " + sheet.getSheetName() + "\n\n");
            if (ocr!=null && sheet instanceof org.apache.poi.xssf.usermodel.XSSFSheet xs && xs.getDrawingPatriarch()!=null)
                sheetPictures(xs.getDrawingPatriarch(),ocr,sheet.getSheetName(),out);
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

    record Hit(Path md, Path source, String title, String section, String excerpt) {
        @Override public String toString() { return title + "   " + section; }
    }

    static List<Hit> search(Path library, String query, int limit) throws IOException {
        return SearchIndex.search(library, query, SearchIndex.Scope.ALL, limit);
    }
}
