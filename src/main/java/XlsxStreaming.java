import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.filesystem.DocumentFactoryHelper;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static org.apache.poi.extractor.ExtractorFactory.OOXML_PACKAGE;

final class XlsxStreaming {
    static final String MAIN_NS = XSSFRelation.NS_SPREADSHEETML;
    static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final int MAX_COLUMNS = SpreadsheetVersion.EXCEL2007.getMaxColumns();
    static final int MAX_ROWS = SpreadsheetVersion.EXCEL2007.getMaxRows();
    // A rich string can encode each of the 32,767 Excel characters as seven XML characters.
    private static final int MAX_CELL_XML_TEXT = 262_144;
    private static final Pattern ESCAPED_CHARACTER = Pattern.compile("_x([0-9A-Fa-f]{4})_");

    static void write(Path file, Path workDir, Writer out, PictureAssets pictures) throws Exception {
        scan(file,workDir,out,pictures,null);
    }

    static List<EvidenceIndex.Edge> relations(Path snapshot) throws IOException {
        var edges = new ArrayList<EvidenceIndex.Edge>();
        try {
            scan(snapshot,snapshot.getParent(),null,null,edges);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Excel 원본 셀을 읽지 못했습니다.",e); }
        return edges;
    }

    private static void scan(Path file, Path workDir, Writer out, PictureAssets pictures,
                             List<EvidenceIndex.Edge> edges) throws Exception {
        if (FileMagic.valueOf(file.toFile()) == FileMagic.OLE2) {
            writeOleContainer(file, workDir, out, pictures,edges);
        } else {
            writeZip(file, workDir, out, pictures,edges);
        }
    }

    private static void writeOleContainer(Path file, Path workDir, Writer out, PictureAssets pictures,List<EvidenceIndex.Edge> edges) throws Exception {
        try (var container = new POIFSFileSystem(file.toFile(), true)) {
            var root = container.getRoot();
            if (root.hasEntryCaseInsensitive(OOXML_PACKAGE) || root.hasEntryCaseInsensitive(Decryptor.DEFAULT_POIFS_ENTRY)) {
                // OPCPackage uses random access on a file. Save the unwrapped ZIP without buffering it in the heap.
                try (InputStream in = DocumentFactoryHelper.getDecryptedStream(root, null)) {
                    Path unpacked = Files.createTempFile(workDir, ".xlsx-package-", ".tmp");
                    try {
                        try (var output = Files.newOutputStream(unpacked)) {
                            in.transferTo(output);
                        }
                        writeZip(unpacked, workDir, out, pictures,edges);
                    } finally {
                        Files.deleteIfExists(unpacked);
                    }
                }
            } else if (root.hasEntryCaseInsensitive("Workbook") || root.hasEntryCaseInsensitive("Book")) {
                try (var workbook = new HSSFWorkbook(root, true)) {
                    if (edges == null) Library.spreadsheet(workbook, out, pictures);
                    else ExcelRelations.scanWorkbook(workbook,edges);
                }
            } else {
                throw new IOException("XLSX 컨테이너에서 통합 문서를 찾을 수 없습니다.");
            }
        }
    }

    private static void writeZip(Path file, Path workDir, Writer out, PictureAssets pictures,List<EvidenceIndex.Edge> edges) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            var reader = new XSSFReader(pkg);
            var workbook = new WorkbookProperties();
            try (InputStream in = reader.getWorkbookData()) {
                parse(in, workbook);
            }
            var styles = new NumberFormats();
            try (InputStream in = reader.getStylesData()) {
                if (in != null) parse(in, styles);
            }
            try (var strings = new XlsxStreamingStrings(workDir);
                 InputStream in = reader.getSharedStringsData()) {
                if (in != null) strings.load(in);
                var sheets = reader.getSheetIterator();
                while (sheets.hasNext()) {
                    try (InputStream sheet = sheets.next()) {
                        String name = sheets.getSheetName();
                        if (edges != null) {
                            if (!ExcelRelations.example(name)) {
                                var scan = new ExcelRelations.SheetScan(name,edges);
                                parse(sheet,new Worksheet(null,strings,styles,workbook.date1904,scan));
                                scan.finish();
                            }
                            continue;
                        }
                        out.write("\n## " + Library.sheetSection(name) + "\n\n");
                        Path spool = Files.createTempFile(workDir, ".xlsx-cells-", ".tmp");
                        try {
                            Worksheet handler;
                            try (var data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(spool)))) {
                                handler = new Worksheet(data, strings, styles, workbook.date1904,null);
                                parse(sheet, handler);
                            }
                            if (handler.drawingId != null) {
                                XlsxStreamingPictures.write(sheets.getSheetPart(), handler.drawingId,
                                        handler.geometry, name, pictures, out);
                            }
                            handler.render(spool, out);
                        } finally {
                            Files.deleteIfExists(spool);
                        }
                    }
                }
            }
        }
    }

    static void parse(InputStream in, DefaultHandler handler) throws IOException {
        try {
            var reader = XMLHelper.newXMLReader();
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            reader.setContentHandler(handler);
            reader.parse(new InputSource(in));
        } catch (SAXException | ParserConfigurationException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("XLSX XML을 읽을 수 없습니다.", e);
        }
    }

    static void appendText(StringBuilder target, char[] data, int start, int length) throws SAXException {
        if (target.length() + length > MAX_CELL_XML_TEXT) throw new SAXException("XLSX 셀 텍스트 길이 초과");
        target.append(data, start, length);
    }

    static String unescapeExcel(String text) {
        if (!text.contains("_x")) return text;
        return ESCAPED_CHARACTER.matcher(text).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(Character.toString((char) Integer.parseInt(match.group(1), 16))));
    }

    // The spool only contains populated cells. It keeps the worksheet scan single-pass even when
    // an explicitly styled blank cell near the end changes the table width.
    private static final class Worksheet extends DefaultHandler {
        private final DataOutputStream data;
        private final ExcelRelations.SheetScan scan;
        private final XlsxStreamingStrings strings;
        private final NumberFormats styles;
        private final XlsxStreamingNumbers display;
        private final XlsxStreamingPictures.Geometry geometry;
        private final StringBuilder value = new StringBuilder();
        private final StringBuilder formula = new StringBuilder();
        private final StringBuilder inline = new StringBuilder();
        private final Map<Long, Shared> shared = new HashMap<>();
        private final List<ArrayFormula> arrays = new ArrayList<>();
        private final int[] columnStyles = new int[MAX_COLUMNS];
        private int depth;
        private int sheetDataDepth = -1, rowDepth = -1, cellDepth = -1;
        private int inlineDepth = -1, phoneticDepth = -1, textDepth = -1;
        private StringBuilder capture;
        private boolean worksheet, hasValue, hasFormula, hasInline;
        private int row = -1, column, nextColumn;
        private int first = MAX_COLUMNS, last;
        private long cellsWritten;
        private String type, style, formulaType, formulaRef, sharedId, drawingId;

        Worksheet(DataOutputStream data, XlsxStreamingStrings strings, NumberFormats styles,
                  boolean date1904, ExcelRelations.SheetScan scan) {
            this.scan = scan;
            this.data = data;
            this.strings = strings;
            this.styles = styles;
            display = new XlsxStreamingNumbers(date1904);
            geometry = new XlsxStreamingPictures.Geometry();
        }

        @Override
        public void startElement(String ns, String name, String qualified, Attributes attributes) throws SAXException {
            depth++;
            if (!MAIN_NS.equals(ns)) return;
            try {
                if (scan != null && worksheet && name.equals("mergeCell")) scan.merge(CellRangeAddress.valueOf(attributes.getValue("ref")));
                if (depth == 1) {
                    worksheet = name.equals("worksheet");
                } else if (depth == 2) {
                    if (worksheet && name.equals("sheetData")) sheetDataDepth = depth;
                    if (name.equals("drawing") && drawingId == null) {
                        drawingId = attributes.getValue(REL_NS, "id");
                    }
                    if (name.equals("sheetFormatPr")) geometry.defaults(attributes);
                } else if (sheetDataDepth == depth - 1 && name.equals("row")) {
                    rowDepth = depth;
                    String reference = attributes.getValue("r");
                    row = reference == null ? row + 1 : Integer.parseInt(reference) - 1;
                    if (row < 0 || row >= MAX_ROWS) throw new SAXException("잘못된 XLSX 행 번호");
                    nextColumn = 0;
                    if (!arrays.isEmpty()) arrays.removeIf(array -> array.range.getLastRow() < row);
                    geometry.row(row, attributes.getValue("ht"));
                } else if (rowDepth == depth - 1 && name.equals("c")) {
                    startCell(attributes);
                } else if (cellDepth == depth - 1) {
                    startCellContent(name, attributes);
                } else if (inlineDepth > 0) {
                    if (name.equals("rPh")) phoneticDepth = depth;
                    if (name.equals("t") && phoneticDepth < 0) collect(inline);
                } else if (worksheet && depth == 3 && sheetDataDepth < 0 && name.equals("col")) {
                    int min = Integer.parseInt(attributes.getValue("min")) - 1;
                    int max = Integer.parseInt(attributes.getValue("max"));
                    if (min < 0 || max > MAX_COLUMNS || min >= max) throw new SAXException("잘못된 XLSX 열 범위");
                    String columnStyle = attributes.getValue("style");
                    if (columnStyle != null) Arrays.fill(columnStyles, min, max, Integer.parseInt(columnStyle));
                    geometry.columns(min, max, attributes.getValue("width"));
                }
            } catch (IllegalArgumentException e) {
                throw new SAXException("잘못된 XLSX 셀 속성", e);
            }
        }

        private void startCell(Attributes attributes) throws SAXException {
            cellDepth = depth;
            String reference = attributes.getValue("r");
            column = reference == null ? nextColumn : columnOf(reference);
            if (column < 0 || column >= MAX_COLUMNS) throw new SAXException("잘못된 XLSX 열 번호");
            nextColumn = Math.max(nextColumn, column + 1);
            first = Math.min(first, column);
            last = Math.max(last, column + 1);
            type = attributes.getValue("t");
            style = attributes.getValue("s");
            hasValue = hasFormula = hasInline = false;
        }

        private void startCellContent(String name, Attributes attributes) {
            switch (name) {
                case "v" -> {
                    hasValue = true;
                    value.setLength(0);
                    collect(value);
                }
                case "f" -> {
                    formulaType = attributes.getValue("t");
                    hasFormula = !"dataTable".equals(formulaType);
                    if (!hasFormula) return;
                    formulaRef = attributes.getValue("ref");
                    sharedId = attributes.getValue("si");
                    formula.setLength(0);
                    collect(formula);
                }
                case "is" -> {
                    hasInline = true;
                    inlineDepth = depth;
                    inline.setLength(0);
                }
                default -> { }
            }
        }

        private void collect(StringBuilder text) {
            capture = text;
            textDepth = depth;
        }

        @Override
        public void characters(char[] chars, int start, int length) throws SAXException {
            if (capture != null) appendText(capture, chars, start, length);
        }

        @Override
        public void endElement(String ns, String name, String qualified) throws SAXException {
            try {
                if (depth == textDepth) {
                    capture = null;
                    textDepth = -1;
                }
                if (depth == phoneticDepth) phoneticDepth = -1;
                if (depth == inlineDepth) inlineDepth = -1;
                if (depth == cellDepth) {
                    finishCell();
                    cellDepth = -1;
                }
                if (depth == rowDepth) rowDepth = -1;
                if (depth == sheetDataDepth) sheetDataDepth = -1;
                depth--;
            } catch (IOException | IllegalArgumentException e) {
                throw new SAXException(e);
            }
        }

        private void finishCell() throws IOException {
            if (!hasValue && !hasInline && !hasFormula && arrays.isEmpty() && !"b".equals(type)) return;
            String expression = hasFormula ? formula.toString() : null;
            long sharedIndex = -1;
            if (hasFormula && "shared".equals(formulaType)) {
                sharedIndex = Long.parseLong(sharedId);
                if (expression != null && !expression.isEmpty() && formulaRef != null) {
                    var range = CellRangeAddress.valueOf(formulaRef);
                    shared.put(sharedIndex, new Shared(Math.max(row, range.getFirstRow()),
                            Math.max(column, range.getFirstColumn()), expression));
                }
            }
            if (hasFormula && "array".equals(formulaType) && formulaRef != null && !expression.isEmpty()) {
                arrays.add(new ArrayFormula(CellRangeAddress.valueOf(formulaRef), expression));
            }
            if (expression == null || expression.isEmpty()) {
                for (ArrayFormula array : arrays) {
                    if (array.range.isInRange(row, column)) {
                        expression = array.formula;
                        break;
                    }
                }
            }
            if (!hasValue && !hasInline && expression == null && !"b".equals(type)) return;
            String formatted = formatValue(expression != null);
            if (expression == null && formatted.isBlank()) return;
            if (scan != null) {
                scan.cell(row,column,expression != null || "e".equals(type) ? "#UNREADABLE" : formatted);
                return;
            }
            data.writeInt(row);
            data.writeInt(column);
            writeText(data, formatted);
            data.writeBoolean(expression != null);
            if (expression != null) {
                data.writeLong(sharedIndex);
                writeText(data, expression);
            }
            cellsWritten++;
        }

        private String formatValue(boolean isFormula) throws IOException {
            String raw = hasValue ? value.toString() : "";
            return switch (type == null ? "n" : type) {
                case "s" -> raw.isEmpty() ? "" : strings.get(Integer.parseInt(raw.strip()));
                case "inlineStr" -> unescapeExcel(hasInline ? inline.toString() : raw);
                case "str" -> unescapeExcel(raw);
                case "b" -> "1".equals(raw) ? "TRUE" : "FALSE";
                case "e", "d" -> raw;
                case "n" -> {
                    if (!hasValue && !isFormula) yield "";
                    int styleId = style == null ? columnStyles[column] : Integer.parseInt(style);
                    int formatId = styles.id(styleId);
                    yield display.format(raw.isEmpty() ? 0 : Double.parseDouble(raw.strip()),
                            formatId, styles.code(formatId));
                }
                default -> throw new IOException("지원하지 않는 XLSX 셀 형식: " + type);
            };
        }

        void render(Path spool, Writer out) throws IOException {
            if (last == 0) return;
            int width = last - first;
            boolean grid = width <= 32;
            if (grid) {
                out.write("| 행 |");
                for (int col = first; col < last; col++) out.write(" " + CellReference.convertNumToColString(col) + " |");
                out.write("\n|" + " --- |".repeat(width + 1) + "\n");
            } else {
                out.write("| 셀 주소 | 내용 |\n| --- | --- |\n");
            }
            String[] cells = grid ? new String[width] : null;
            int previousRow = -1;
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(spool)))) {
                for (long i = 0; i < cellsWritten; i++) {
                    int row = in.readInt(), column = in.readInt();
                    String formatted = readText(in);
                    if (in.readBoolean()) {
                        long sharedId = in.readLong();
                        String expression = readText(in);
                        if (sharedId >= 0 && expression.isEmpty()) {
                            Shared master = shared.get(sharedId);
                            if (master == null) throw new IOException("XLSX 공유 수식 원본을 찾을 수 없습니다.");
                            expression = XlsxStreamingFormula.shift(master.formula, row - master.row, column - master.column);
                        }
                        formatted += " (=" + expression + ")";
                    }
                    String address = new CellReference(row, column).formatAsString();
                    if (grid) {
                        if (previousRow >= 0 && previousRow != row) {
                            writeRow(previousRow, cells, out);
                            Arrays.fill(cells, null);
                        }
                        cells[column - first] = address + " · " + Library.tableCell(formatted);
                        previousRow = row;
                    } else {
                        out.write("| " + address + " | " + Library.tableCell(formatted) + " |\n");
                    }
                }
            }
            if (previousRow >= 0) writeRow(previousRow, cells, out);
        }
    }

    private static void writeRow(int row, String[] cells, Writer out) throws IOException {
        out.write("| " + (row + 1) + " |");
        for (String cell : cells) out.write(" " + (cell == null ? "" : cell) + " |");
        out.write("\n");
    }

    static int columnOf(String reference) {
        int column = 0;
        for (int i = 0; i < reference.length(); i++) {
            char ch = reference.charAt(i);
            if (ch >= 'a' && ch <= 'z') ch -= 'a' - 'A';
            if (ch < 'A' || ch > 'Z') break;
            column = column * 26 + ch - 'A' + 1;
            if (column > MAX_COLUMNS) return -1;
        }
        return column - 1;
    }

    static void writeText(java.io.DataOutput out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static String readText(java.io.DataInput in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record Shared(int row, int column, String formula) { }

    private record ArrayFormula(CellRangeAddress range, String formula) { }

    private static final class WorkbookProperties extends DefaultHandler {
        private boolean date1904;
        private int depth;

        @Override
        public void startElement(String ns, String name, String qualified, Attributes attributes) throws SAXException {
            depth++;
            if (depth == 1 && !(MAIN_NS.equals(ns) && "workbook".equals(name))) {
                throw new SAXException(new IOException("XLSX 통합 문서 형식이 아닙니다."));
            }
            if (depth == 2 && MAIN_NS.equals(ns) && name.equals("workbookPr")) {
                String value = attributes.getValue("date1904");
                if (value != null) {
                    value = value.strip();
                    date1904 = "1".equals(value) || "true".equals(value);
                }
            }
        }

        @Override
        public void endElement(String ns, String name, String qualified) {
            depth--;
        }
    }

    private static final class NumberFormats extends DefaultHandler {
        private final Map<Integer, String> custom = new HashMap<>();
        private final List<Integer> styles = new ArrayList<>();
        private int depth;
        private String section;

        @Override
        public void startElement(String ns, String name, String qualified, Attributes attributes) {
            depth++;
            if (!MAIN_NS.equals(ns)) return;
            if (depth == 2) {
                section = name;
            } else if (depth == 3 && "numFmts".equals(section) && name.equals("numFmt")) {
                custom.put(Integer.parseInt(attributes.getValue("numFmtId")), attributes.getValue("formatCode"));
            } else if (depth == 3 && "cellXfs".equals(section) && name.equals("xf")) {
                String id = attributes.getValue("numFmtId");
                styles.add(id == null ? 0 : Integer.parseInt(id));
            }
        }

        @Override
        public void endElement(String ns, String name, String qualified) {
            if (depth == 2) section = null;
            depth--;
        }

        int id(int style) {
            return style >= 0 && style < styles.size() ? styles.get(style) : 0;
        }

        String code(int id) {
            return custom.getOrDefault(id, BuiltinFormats.getBuiltinFormat(id));
        }
    }
}
