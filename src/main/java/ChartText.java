import java.io.IOException;
import java.io.Writer;
import java.util.*;
import javax.xml.xpath.*;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Read embedded chart labels and cached series values without opening linked workbooks. */
final class ChartText {
    static void write(XSLFChart chart, Writer out) throws IOException {
        XPath xpath = XPathFactory.newInstance().newXPath();
        Node root = chart.getCTChart().getDomNode();
        try {
            StringBuilder title = new StringBuilder();
            for (Node piece : nodes(xpath, root, "./*[local-name()='title']//*[local-name()='t' or local-name()='v']/text()"))
                title.append(piece.getNodeValue());
            out.write("차트" + (title.isEmpty() ? "" : " · " + Library.plain(title.toString())) + "\n\n");
            int number = 0;
            for (Node series : nodes(xpath, root, "./*[local-name()='plotArea']/*/*[local-name()='ser']")) {
                number++;
                String label = xpath.evaluate("(.//*[local-name()='tx']//*[local-name()='v'])[1]", series);
                out.write("계열 · " + (label.isBlank() ? number : Library.plain(label)) + "\n\n");
                // OOXML point indices align categories and values even when a series contains gaps.
                Map<Integer, List<String>> rows = new TreeMap<>();
                for (Node field : nodes(xpath, series, "./*[local-name()='cat' or local-name()='val' or local-name()='xVal' or local-name()='yVal' or local-name()='bubbleSize']")) {
                    for (Node point : nodes(xpath, field, ".//*[local-name()='pt']")) {
                        int index = Integer.parseInt(xpath.evaluate("@idx", point));
                        String value = xpath.evaluate("./*[local-name()='v']", point);
                        rows.computeIfAbsent(index, unused -> new ArrayList<>()).add(value);
                    }
                }
                for (var entry : rows.entrySet())
                    out.write("- " + Library.plain(String.join(" · ", entry.getValue())) + "\n");
                out.write("\n");
            }
        } catch (XPathExpressionException | NumberFormatException e) {
            throw new IOException("차트 데이터 읽기 실패", e);
        }
    }

    private static List<Node> nodes(XPath xpath, Node context, String path) throws XPathExpressionException {
        NodeList found = (NodeList) xpath.evaluate(path, context, XPathConstants.NODESET);
        List<Node> result = new ArrayList<>(found.getLength());
        for (int i = 0; i < found.getLength(); i++) result.add(found.item(i));
        return result;
    }
}
