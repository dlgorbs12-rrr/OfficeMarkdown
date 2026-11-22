import org.apache.poi.xssf.usermodel.XSSFChildAnchor;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(IsolatedSearchIndex.class)
class ShapeTextTest {
    @TempDir Path root;

    private static final String NAMESPACES = """
             xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
             xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
             xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
             xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
             xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
             xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
             xmlns:v="urn:schemas-microsoft-com:vml"
             xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math\"""";

    // An xml-fragment root sets the paragraph's children; a w:p root would nest a paragraph inside it.
    private static CTP paragraph(String children) throws org.apache.xmlbeans.XmlException {
        return CTP.Factory.parse("<xml-fragment " + NAMESPACES + ">" + children + "</xml-fragment>");
    }

    private static String textBox(String text) {
        return "<wps:wsp><wps:spPr/><wps:txbx><w:txbxContent><w:p><w:r><w:t>" + text
                + "</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr/></wps:wsp>";
    }

    private static String drawing(String graphic) {
        return "<w:drawing><wp:anchor><wp:extent cx=\"1\" cy=\"1\"/><wp:docPr id=\"1\" name=\"s\"/><a:graphic>"
                + "<a:graphicData uri=\"x\">" + graphic + "</a:graphicData></a:graphic></wp:anchor></w:drawing>";
    }

    @Test void wordTextBoxesCanvasShapesAndEquationsAreConvertedOnce() throws Exception {
        Path source = root.resolve("구성도.docx");
        // Word writes each floating shape twice: a DrawingML choice and a VML fallback with the same text.
        String floating = "<w:r><mc:AlternateContent><mc:Choice Requires=\"wps\">"
                + drawing(textBox("인증 서버"))
                + "</mc:Choice><mc:Fallback><w:pict><v:rect><v:textbox><w:txbxContent><w:p><w:r><w:t>인증 서버</w:t>"
                + "</w:r></w:p></w:txbxContent></v:textbox></v:rect></w:pict></mc:Fallback></mc:AlternateContent></w:r>";
        String canvas = "<w:r><mc:AlternateContent><mc:Choice Requires=\"wpc\">" + drawing("<wpc:wpc><wpc:bg/>"
                + textBox("사용자 PC") + textBox("업무 시스템") + "</wpc:wpc>")
                + "</mc:Choice><mc:Fallback><w:drawing/></mc:Fallback></mc:AlternateContent></w:r>";
        String equation = "<w:r><w:t>가용성 </w:t></w:r><m:oMath><m:r><m:t>A=</m:t></m:r>"
                + "<m:f><m:fPr><m:ctrlPr/></m:fPr><m:num><m:r><m:t>MTBF</m:t></m:r></m:num>"
                + "<m:den><m:r><m:t>MTBF+MTTR</m:t></m:r></m:den></m:f></m:oMath>";
        try (var document = new XWPFDocument(); var output = Files.newOutputStream(source)) {
            for (String xml : new String[] {floating, canvas, equation})
                document.createParagraph().getCTP().set(paragraph(xml));
            var table = document.createTable(1, 1);
            table.getRow(0).getCell(0).getParagraphs().getFirst().getCTP()
                    .set(paragraph("<w:r>" + drawing(textBox("표 안 도형")) + "</w:r>"));
            document.write(output);
        }

        String markdown = Files.readString(Library.convert(source, root.resolve("lib")));

        assertEquals(1, count(markdown, "인증 서버"), markdown);
        assertTrue(markdown.contains("사용자 PC\n\n업무 시스템"), markdown);
        assertEquals(1, count(markdown, "사용자 PC"), markdown);
        assertTrue(markdown.contains("가용성\n\nA=MTBF/(MTBF+MTTR)"), markdown);
        // A drawing placed directly in a run is already in the paragraph text through POI and must not repeat.
        assertEquals(1, count(markdown, "표 안 도형"), markdown);
    }

    @Test void excelShapeTextIncludingGroupsIsConvertedAheadOfTheCells() throws Exception {
        Path source = root.resolve("구성도.xlsx");
        try (var book = new XSSFWorkbook(); var output = Files.newOutputStream(source)) {
            var sheet = book.createSheet("구성");
            sheet.createRow(0).createCell(0).setCellValue("구성도 제목");
            var drawing = sheet.createDrawingPatriarch();
            drawing.createSimpleShape(new XSSFClientAnchor(0, 0, 0, 0, 1, 1, 3, 3)).setText("로드밸런서");
            drawing.createTextbox(new XSSFClientAnchor(0, 0, 0, 0, 4, 1, 6, 3)).setText("인증 DB");
            drawing.createConnector(new XSSFClientAnchor(0, 0, 0, 0, 3, 2, 4, 2));
            var group = drawing.createGroup(new XSSFClientAnchor(0, 0, 0, 0, 1, 5, 6, 8));
            group.createSimpleShape(new XSSFChildAnchor(0, 0, 100, 100)).setText("퇴사 등록");
            group.createSimpleShape(new XSSFChildAnchor(0, 0, 100, 100)).setText("계정 잠금");
            book.write(output);
        }

        String markdown = Files.readString(Library.convert(source, root.resolve("lib")));

        assertTrue(markdown.contains("로드밸런서\n\n인증 DB\n\n퇴사 등록\n\n계정 잠금"), markdown);
        assertTrue(markdown.indexOf("계정 잠금") < markdown.indexOf("구성도 제목"), markdown);
        SearchIndex.indexCompleted(root.resolve("lib"), java.util.List.of(Library.convert(source, root.resolve("lib"))));
        assertFalse(SearchIndex.search(root.resolve("lib"), "로드밸런서", SearchIndex.Scope.CONTENT, 10).isEmpty());
    }

    private static int count(String text, String word) {
        return (int) Pattern.compile(Pattern.quote(word)).matcher(text).results().count();
    }
}
