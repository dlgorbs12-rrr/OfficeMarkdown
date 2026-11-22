import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.Arrays;
import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTAbsoluteAnchor;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTDrawing;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTGroupShape;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTMarker;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTOneCellAnchor;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTPicture;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTShape;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.CTTwoCellAnchor;
import org.xml.sax.Attributes;

final class XlsxStreamingPictures {
    private static final String COMPATIBILITY_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final XmlOptions XML_OPTIONS = new XmlOptions(POIXMLTypeLoader.DEFAULT_XML_OPTIONS)
            .setDisallowDocTypeDeclaration(true).setLoadDTDGrammar(false).setLoadExternalDTD(false)
            .setLoadReplaceDocumentElement(null);

    static void write(PackagePart sheet, String drawingId, Geometry geometry, String sheetName, PictureAssets pictures,
                      Writer out) throws IOException, InvalidFormatException, XmlException {
        PackagePart drawing = related(sheet, drawingId, XSSFRelation.DRAWINGS.getRelation());
        if (drawing == null) return;
        CTDrawing anchors;
        try (InputStream in = drawing.getInputStream()) {
            anchors = CTDrawing.Factory.parse(in, XML_OPTIONS);
        }
        var texts = new java.util.ArrayList<String>();
        writeAnchors(anchors, drawing, geometry, sheetName, pictures, texts, out);
        for (String text : texts) out.write(Library.plain(text) + "\n\n");
    }

    private static void writeAnchors(CTDrawing anchors, PackagePart drawing, Geometry geometry, String sheet,
                                     PictureAssets pictures, java.util.List<String> texts, Writer out) throws IOException, InvalidFormatException, XmlException {
        try (var cursor = anchors.newCursor()) {
            if (!cursor.toFirstChild()) return;
            do {
                var child = cursor.getObject();
                if (child instanceof CTTwoCellAnchor || child instanceof CTOneCellAnchor || child instanceof CTAbsoluteAnchor) {
                    writeShapes(child, drawing, geometry, sheet, pictures, texts, out);
                } else if (cursor.getName() != null && COMPATIBILITY_NS.equals(cursor.getName().getNamespaceURI())
                        && "AlternateContent".equals(cursor.getName().getLocalPart())) {
                    cursor.push();
                    if (cursor.toFirstChild()) {
                        writeAnchors(CTDrawing.Factory.parse(cursor.newXMLStreamReader(), XML_OPTIONS),
                                drawing, geometry, sheet, pictures, texts, out);
                    }
                    cursor.pop();
                }
            } while (cursor.toNextSibling());
        }
    }

    private static void writeShapes(XmlObject parent, PackagePart drawing, Geometry geometry, String sheet,
                                    PictureAssets pictures, java.util.List<String> texts, Writer out) throws IOException, InvalidFormatException {
        try (var cursor = parent.newCursor()) {
            if (!cursor.toFirstChild()) return;
            do {
                var shape = cursor.getObject();
                if (shape instanceof CTGroupShape group) {
                    writeShapes(group, drawing, geometry, sheet, pictures, texts, out);
                } else if (shape instanceof CTShape drawn) {
                    // Shape text is collected and written after the pictures, ahead of the cell table.
                    if (drawn.getTxBody() != null) {
                        String text = ShapeText.paragraphs(drawn.getTxBody().getDomNode(), ShapeText.DRAWING);
                        if (!text.isEmpty()) texts.add(text);
                    }
                } else if (shape instanceof CTPicture picture) {
                    var fill = picture.getBlipFill();
                    if (fill == null || fill.getBlip() == null) continue;
                    PackagePart image = related(drawing, fill.getBlip().getEmbed(), XSSFRelation.IMAGES.getRelation());
                    if (image == null) continue;
                    String location = pictureLocation(parent, geometry, sheet);
                    try (InputStream in = image.getInputStream()) {
                        byte[] data = IOUtils.toByteArrayWithMaxLength(in, XSSFPictureData.getMaxImageSize());
                        pictures.write(data, image.getPartName().getExtension(), Library.sheetSection(sheet), location, out);
                    }
                }
            } while (cursor.toNextSibling());
        }
    }

    private static PackagePart related(PackagePart source, String id, String type) throws InvalidFormatException {
        if (id == null) return null;
        var relationship = source.getRelationship(id);
        if (relationship == null || relationship.getTargetMode() != TargetMode.INTERNAL
                || !type.equals(relationship.getRelationshipType())) return null;
        return source.getRelatedPart(relationship);
    }

    private static String pictureLocation(XmlObject parent, Geometry geometry, String sheet) throws IOException {
        CTMarker marker = switch (parent) {
            case CTTwoCellAnchor anchor -> anchor.getFrom();
            case CTOneCellAnchor anchor -> anchor.getFrom();
            default -> null;
        };
        if (marker != null) return sheet + " · " + new CellReference(marker.getRow(), marker.getCol()).formatAsString();
        if (parent instanceof CTAbsoluteAnchor anchor && anchor.getPos() != null) {
            var position = anchor.getPos();
            return sheet + " · " + geometry.cell(POIXMLUnits.parseLength(position.xgetX()),
                    POIXMLUnits.parseLength(position.xgetY()));
        }
        return sheet;
    }

    // POI uses sheet column widths and row heights to locate pictures with absolute anchors.
    static final class Geometry {
        private int defaultWidth = Units.columnWidthToEMU(8 * 256);
        private int defaultHeight;
        private int[] columnWidths;
        private int[] rowHeights;

        void defaults(Attributes attributes) {
            String width = attributes.getValue("baseColWidth");
            String height = attributes.getValue("defaultRowHeight");
            if (width != null) defaultWidth = Units.columnWidthToEMU(Integer.parseInt(width) * 256);
            if (height != null) defaultHeight = Units.toEMU(Float.parseFloat(height));
        }

        void columns(int min, int max, String width) {
            if (width == null) return;
            if (columnWidths == null) columnWidths = new int[XlsxStreaming.MAX_COLUMNS];
            int emu = Units.columnWidthToEMU(Math.toIntExact(Math.round(Double.parseDouble(width) * 256)));
            Arrays.fill(columnWidths, min, max, emu + 1);
        }

        void row(int row, String height) {
            if (height == null) return;
            if (rowHeights == null) rowHeights = new int[Math.max(16, row + 1)];
            if (row >= rowHeights.length) rowHeights = Arrays.copyOf(rowHeights,
                    Math.max(row + 1, Math.min(XlsxStreaming.MAX_ROWS, rowHeights.length * 2)));
            rowHeights[row] = Units.toEMU(Float.parseFloat(height)) + 1;
        }

        String cell(long x, long y) throws IOException {
            int col = locate(x, columnWidths, defaultWidth, XlsxStreaming.MAX_COLUMNS);
            int row = locate(y, rowHeights, defaultHeight, XlsxStreaming.MAX_ROWS);
            return new CellReference(row, col).formatAsString();
        }

        private static int locate(long offset, int[] sizes, int defaultSize, int count) throws IOException {
            for (int i = 0; i < count; i++) {
                int stored = sizes != null && i < sizes.length ? sizes[i] : 0;
                offset -= stored == 0 ? defaultSize : stored - 1;
                if (offset <= 0) return i;
            }
            throw new IOException("XLSX 그림 위치가 시트 범위를 벗어났습니다.");
        }
    }
}
