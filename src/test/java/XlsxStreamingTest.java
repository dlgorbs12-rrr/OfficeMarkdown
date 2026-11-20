import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.apache.poi.extractor.ExtractorFactory.OOXML_PACKAGE;

class XlsxStreamingTest {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String SHEET = "xl/worksheets/sheet1.xml";

    @TempDir Path root;

    @Test void styledBlanksPreserveWidthAndTypedValuesMatchTheExistingMarkdownContract() throws Exception {
        Path source = root.resolve("typed.xlsx");
        try (var book = new XSSFWorkbook(); var output = Files.newOutputStream(source)) {
            book.getCTWorkbook().getWorkbookPr().setDate1904(true);
            var date = book.createCellStyle();
            date.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            book.createCellStyle().setDataFormat((short) 4);
            book.createCellStyle().setDataFormat((short) 10);
            book.createSheet("좁은 시트").createRow(0).createCell(0).setCellValue("서울 & <재고>");
            book.createSheet("넓은 시트");
            book.createSheet("빈 시트");
            book.write(output);
        }
        String workbookXml;
        try (var archive = new ZipFile(source.toFile());
             var part = archive.getInputStream(archive.getEntry("xl/workbook.xml"))) {
            workbookXml = new String(part.readAllBytes(), StandardCharsets.UTF_8);
        }
        String paddedDateSetting = workbookXml.replaceFirst("date1904=\"[^\"]*\"", "date1904=\" true \"");
        assertTrue(!workbookXml.equals(paddedDateSetting));
        rewrite(source, Map.of("xl/workbook.xml", paddedDateSetting, SHEET, """
                <worksheet xmlns="%s">
                <cols><col min="7" max="7" style="3"/></cols>
                <sheetData>
                  <row r="2"><c r="C2" s="1"><v>1</v></c><c r="D2" t="s"><v>0</v></c>
                    <c r="E2" s="2"><v>1234.5</v></c><c r="G2"><v>0.1234</v></c><c r="J2" s="1"/></row>
                  <row><c r="C3" t="inlineStr"><is><r><t>인라인 &amp; </t></r><r><t>한글</t></r>
                    <rPh sb="0" eb="1"><t>ruby</t></rPh></is></c>
                    <c t="b"><v>1</v></c><c r="E3" t="e"><v>#DIV/0!</v></c>
                    <c r="F3" t="inlineStr"><is><t>수량|단위_x000D_합계_x005F_x0041_</t></is></c></row>
                  <row r="7"><c r="E7"><f t="shared" si="2"/><v>4</v></c></row>
                  <row r="8"><c r="C8"><f t="shared" ref="C8:C9" si="1">D8+$E$2</f><v>1236.5</v></c>
                    <c r="E8"><f t="shared" ref="E8:E10" si="2">F8</f><v>8</v></c></row>
                  <row r="9"><c r="C9"><f t="shared" si="1"/><v>1237.5</v></c></row>
                  <row r="12"><c r="D12"><f t="array" ref="D12:D13">1+2</f><v>3</v></c></row>
                  <row r="13"><c r="D13"><v>3</v></c></row>
                  <row r="30"><c r="J30" s="2"/></row>
                </sheetData></worksheet>
                """.formatted(NS), "xl/worksheets/sheet2.xml", """
                <worksheet xmlns="%s"><sheetData>
                  <row r="4"><c r="A4" t="str"><f>"A&amp;B"</f><v>A&amp;B</v></c>
                    <c r="B4"><f>1+2</f></c><c r="C4"><v/></c><c r="Z4" t="b"><v>0</v></c></row>
                  <row r="12"><c r="AG12" s="1"/></row>
                </sheetData></worksheet>
                """.formatted(NS)));
        String markdown = Files.readString(Library.convert(source, root.resolve("library")));
        assertEquals("""
                ## 시트 · 좁은 시트

                | 행 | C | D | E | F | G | H | I | J |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | 2 | C2 · 1904-01-02 | D2 · 서울 &amp; &lt;재고&gt; | E2 · 1,234.50 |  | G2 · 12.34% |  |  |  |
                | 3 | C3 · 인라인 &amp; 한글 | D3 · TRUE | E3 · #DIV/0! | F3 · 수량\\|단위<br>합계_x0041_ |  |  |  |  |
                | 7 |  |  | E7 · 4 (=F7) |  |  |  |  |  |
                | 8 | C8 · 1236.5 (=D8+$E$2) |  | E8 · 8 (=F8) |  |  |  |  |  |
                | 9 | C9 · 1237.5 (=D9+$E$2) |  |  |  |  |  |  |  |
                | 12 |  | D12 · 3 (=1+2) |  |  |  |  |  |  |
                | 13 |  | D13 · 3 (=1+2) |  |  |  |  |  |  |

                ## 시트 · 넓은 시트

                | 셀 주소 | 내용 |
                | --- | --- |
                | A4 | A&amp;B (="A&amp;B") |
                | B4 | 0 (=1+2) |
                | C4 | 0 |
                | Z4 | FALSE |

                ## 시트 · 빈 시트

                """, markdown.substring(markdown.indexOf("## 시트")));
    }

    @Test void sharedReferencesShiftWithoutChangingTextNamesOrSheetQualifiers() {
        var source = "LOG10(A1)+$B4+C$5+SUM(D:F,$5:9,'A1'' B2'!G6)+Table1[Cell A1]+N(\"A1\"\" B2\")+A1:B2!G3+LOG10";
        var shifted = "LOG10(C4)+$B7+E$5+SUM(F:H,$5:12,'A1'' B2'!I9)+Table1[Cell A1]+N(\"A1\"\" B2\")+A1:B2!I6+LOI13";
        assertEquals(shifted, XlsxStreamingFormula.shift(source, 3, 2));
    }

    @Test void scaledAndRoundedNumbersMatchCellFormatting() throws Exception {
        Path source = root.resolve("scaled-and-rounded.xlsx");
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(source)) {
            var row = book.createSheet("서식").createRow(0);
            var scaledStyle = book.createCellStyle();
            scaledStyle.setDataFormat(book.createDataFormat().getFormat("#,##0.0,,"));
            var scaled = row.createCell(0);
            scaled.setCellStyle(scaledStyle);
            scaled.setCellValue(1_234_567d);
            var roundedStyle = book.createCellStyle();
            roundedStyle.setDataFormat((short) 1);
            var rounded = row.createCell(1);
            rounded.setCellStyle(roundedStyle);
            rounded.setCellValue(123.49999999999999d);
            book.getStylesSource().putNumberFormat((short) 2, "#,##0.000");
            var overriddenStyle = book.createCellStyle();
            overriddenStyle.setDataFormat((short) 2);
            var overridden = row.createCell(2);
            overridden.setCellStyle(overriddenStyle);
            overridden.setCellValue(1_234.5);
            book.write(out);
        }
        String markdown = sheetBody(Library.convert(source, root.resolve("library")));
        assertTrue(markdown.contains("| 1 | A1 · 1.2 | B1 · 123 | C1 · 1,234.500 |"), markdown);
    }

    @Test void unknownNumberFormatRetainsTheRawNumber() throws Exception {
        Path source = root.resolve("missing-format.xlsx");
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(source)) {
            var cell = book.createSheet("서식").createRow(0).createCell(0);
            var style = book.createCellStyle();
            style.setDataFormat((short) 55);
            cell.setCellStyle(style);
            cell.setCellValue(45_000d);
            book.write(out);
        }
        String markdown = sheetBody(Library.convert(source, root.resolve("library")));
        assertTrue(markdown.contains("| 1 | A1 · 45000.0 |"), markdown);
    }

    @Test void dataTableFormulaMarksOnlyTheCachedResult() throws Exception {
        Path source = root.resolve("data-table.xlsx");
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(source)) {
            book.createSheet("가상 분석");
            book.write(out);
        }
        rewrite(source, Map.of(SHEET, """
                <worksheet xmlns="%s"><sheetData>
                  <row r="2"><c r="B2"><f t="dataTable" ref="B2:C3" dt2D="1" r1="A1" r2="B1"/><v>7</v></c></row>
                </sheetData></worksheet>
                """.formatted(NS)));
        String markdown = sheetBody(Library.convert(source, root.resolve("library")));
        assertTrue(markdown.contains("| 2 | B2 · 7 |"), markdown);
    }

    @Test void differentOfficePackageDoesNotReplaceExistingMarkdown() throws Exception {
        Path source = root.resolve("wrong-extension.xlsx"), library = root.resolve("library");
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(source)) {
            book.createSheet("기존").createRow(0).createCell(0).setCellValue("유지할 문서");
            book.write(out);
        }
        Path markdown = Library.convert(source, library);
        byte[] before = Files.readAllBytes(markdown);
        try (var document = new XWPFDocument(); var out = Files.newOutputStream(source)) {
            document.createParagraph().createRun().setText("스프레드시트가 아닌 문서");
            document.write(out);
        }
        byte[] malformed = Files.readAllBytes(source);
        assertThrows(IOException.class, () -> Library.convert(source, library));
        assertArrayEquals(before, Files.readAllBytes(markdown));
        assertArrayEquals(malformed, Files.readAllBytes(source));
    }

    @Test void imagePositionsFollowWorksheetAndNestedGroupAnchorConventions() throws Exception {
        Path source = root.resolve("image-positions.xlsx");
        try (var book = new XSSFWorkbook(); var output = Files.newOutputStream(source)) {
            var sheet = book.createSheet("배치");
            sheet.setDefaultRowHeightInPoints(15);
            sheet.createRow(0).setHeightInPoints(30);
            sheet.setColumnWidth(0, 20 * 256);
            var drawing = sheet.createDrawingPatriarch();
            int image = book.addPicture(new byte[]{1, 2, 3}, Workbook.PICTURE_TYPE_PNG);
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 3, 2, 4, 3), image);
            var group = drawing.createGroup(new XSSFClientAnchor(0, 0, 0, 0, 8, 4, 9, 5));
            group.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 1, 1), image);
            var xml = drawing.getCTDrawing();
            var first = xml.getTwoCellAnchorArray(0);
            var oneCell = xml.addNewOneCellAnchor();
            var from = oneCell.addNewFrom();
            from.setRow(6);
            from.setCol(4);
            from.setRowOff(0);
            from.setColOff(0);
            oneCell.addNewExt().setCx(5000);
            oneCell.setPic(first.getPic());
            oneCell.addNewClientData();
            var absolute = xml.addNewAbsoluteAnchor();
            var position = absolute.addNewPos();
            position.setX(Units.columnWidthToEMU(20 * 256) + 1);
            position.setY(Units.toEMU(30) + 1);
            absolute.addNewExt().setCy(5000);
            absolute.setPic(first.getPic());
            absolute.addNewClientData();
            book.write(output);
        }
        try (var ocr = new ImageOcr(ImageOcr.Mode.WINDOWS, root)) {
            Library.convert(source, root.resolve("library"), ocr);
            assertEquals(java.util.List.of("배치 · D3", "배치", "배치 · E7", "배치 · B2").stream()
                            .map(location -> location + " · 지원하지 않는 그림 형식: PNG").toList(),
                    new ArrayList<>(ocr.warnings));
        }
    }

    @Test void forbiddenDtdRetainsPreviousMarkdownAndDeletesTemporaryFiles() throws Exception {
        Path source = root.resolve("read-only.xlsx"), library = root.resolve("library");
        try (var book = new XSSFWorkbook(); var output = Files.newOutputStream(source)) {
            book.createSheet().createRow(0).createCell(0).setCellValue("기존 문서");
            book.write(output);
        }
        Path markdown = Library.convert(source, library);
        byte[] previous = Files.readAllBytes(markdown);
        rewrite(source, Map.of(SHEET, """
                <!DOCTYPE worksheet []>
                <worksheet xmlns="%s"><sheetData/></worksheet>
                """.formatted(NS)));
        byte[] input = Files.readAllBytes(source);
        assertThrows(IOException.class, () -> Library.convert(source, library));
        assertArrayEquals(previous, Files.readAllBytes(markdown));
        assertArrayEquals(input, Files.readAllBytes(source));
        try (var files = Files.list(library)) {
            assertEquals(java.util.List.of(markdown), files.toList());
        }
    }

    @Test void oleContainersKeepTheOutputOfTheirWrappedWorkbook() throws Exception {
        Path xlsx = root.resolve("workbook.xlsx"), library = root.resolve("library");
        try (var workbook = new XSSFWorkbook(); var out = Files.newOutputStream(xlsx)) {
            workbook.createSheet("내용").createRow(1).createCell(1).setCellValue("포장된 통합 문서");
            workbook.write(out);
        }
        String expected = sheetBody(Library.convert(xlsx, library));
        for (boolean encrypted : new boolean[]{false, true}) {
            Path wrapped = root.resolve(encrypted ? "default-encrypted.xlsx" : "package.xlsx");
            try (var container = new POIFSFileSystem(); var out = Files.newOutputStream(wrapped)) {
                if (encrypted) {
                    var encryption = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
                    encryption.confirmPassword(Decryptor.DEFAULT_PASSWORD);
                    try (var encryptedZip = encryption.getDataStream(container)) {
                        Files.copy(xlsx, encryptedZip);
                    }
                } else {
                    try (var zip = Files.newInputStream(xlsx)) {
                        container.getRoot().createDocument(OOXML_PACKAGE, zip);
                    }
                }
                container.writeFilesystem(out);
            }
            byte[] before = Files.readAllBytes(wrapped);
            assertEquals(expected, sheetBody(Library.convert(wrapped, library)));
            assertArrayEquals(before, Files.readAllBytes(wrapped));
        }
        try (var files = Files.list(library)) {
            var outputs = files.toList();
            assertEquals(3, outputs.size());
            assertEquals(3, outputs.stream().filter(path -> path.getFileName().toString().endsWith(".md")).count());
        }
    }

    private static String sheetBody(Path markdown) throws IOException {
        String text = Files.readString(markdown);
        return text.substring(text.indexOf("## 시트"));
    }

    private static void rewrite(Path source, Map<String, String> replacements) throws IOException {
        Path edited = Files.createTempFile(source.getParent(), "rewrite-", ".xlsx");
        try (var zip = new ZipFile(source.toFile());
             var output = new ZipOutputStream(Files.newOutputStream(edited))) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                String replacement = replacements.get(entry.getName());
                if (replacement == null) {
                    try (var input = zip.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                } else {
                    output.write(replacement.getBytes(StandardCharsets.UTF_8));
                }
                output.closeEntry();
            }
        }
        Files.move(edited, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
