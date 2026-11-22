import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Node;

/** Text that Word and Excel keep inside drawn shapes and equations, which the document models do not expose. */
final class ShapeText {
    static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String MATH = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    private static final String COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    private ShapeText() {}

    /**
     * One entry per text box, canvas shape or group member that XWPFParagraph.getText() leaves out. POI already
     * reads drawings and VML pictures placed directly in a run, but not those Word wraps in mc:AlternateContent.
     * The VML fallback inside AlternateContent repeats the same boxes, so it is skipped too.
     */
    static List<String> textBoxes(Node node) {
        var boxes = new ArrayList<String>();
        textBoxes(node, boxes);
        return boxes;
    }

    private static void textBoxes(Node node, List<String> boxes) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE || is(child, COMPATIBILITY, "Fallback")) continue;
            if ((is(child, WORD, "drawing") || is(child, WORD, "pict")) && is(node, WORD, "r")) continue;
            if (is(child, WORD, "txbxContent")) {
                String text = paragraphs(child, WORD);
                if (!text.isEmpty()) boxes.add(text);
            } else {
                textBoxes(child, boxes);
            }
        }
    }

    /** Paragraph texts under the node joined by spaces, where paragraphs and runs use the given namespace. */
    static String paragraphs(Node node, String namespace) {
        var text = new StringBuilder();
        collectParagraphs(node, namespace, text);
        return text.toString().strip();
    }

    private static void collectParagraphs(Node node, String namespace, StringBuilder text) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (is(child, namespace, "p")) {
                var paragraph = new StringBuilder();
                runs(child, namespace, paragraph);
                String line = paragraph.toString().strip();
                if (!line.isEmpty()) text.append(text.isEmpty() ? "" : " ").append(line);
            } else {
                collectParagraphs(child, namespace, text);
            }
        }
    }

    private static void runs(Node node, String namespace, StringBuilder text) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE || is(child, COMPATIBILITY, "Fallback")) continue;
            if (is(child, namespace, "t")) text.append(text(child));
            else if (is(child, namespace, "tab") || is(child, namespace, "br")) text.append(' ');
            else runs(child, namespace, text);
        }
    }

    /** Equations in linear form such as {@code A=MTBF/(MTBF+MTTR)}. */
    static List<String> equations(Node node) {
        var equations = new ArrayList<String>();
        equations(node, equations);
        return equations;
    }

    private static void equations(Node node, List<String> equations) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE || is(child, COMPATIBILITY, "Fallback")) continue;
            if (is(child, MATH, "oMath")) {
                String text = math(child).strip();
                if (!text.isEmpty()) equations.add(text);
            } else {
                equations(child, equations);
            }
        }
    }

    private static String math(Node node) {
        if (MATH.equals(node.getNamespaceURI())) {
            switch (node.getLocalName()) {
                case "t" -> { return text(node); }
                case "f" -> { return group(part(node, "num")) + "/" + group(part(node, "den")); }
                case "sSup" -> { return part(node, "e") + "^" + group(part(node, "sup")); }
                case "sSub" -> { return part(node, "e") + "_" + group(part(node, "sub")); }
                case "sSubSup" -> { return part(node, "e") + "_" + group(part(node, "sub")) + "^" + group(part(node, "sup")); }
                case "rad" -> {
                    String degree = part(node, "deg");
                    return (degree.isEmpty() ? "" : group(degree)) + "√" + group(part(node, "e"));
                }
                case "d" -> {
                    var properties = child(node, MATH, "dPr");
                    String begin = delimiter(properties, "begChr", "("), end = delimiter(properties, "endChr", ")");
                    var items = new ArrayList<String>();
                    for (Node item = node.getFirstChild(); item != null; item = item.getNextSibling())
                        if (is(item, MATH, "e")) items.add(math(item));
                    return begin + String.join(",", items) + end;
                }
                default -> { }
            }
        }
        if (node.getLocalName() != null && node.getLocalName().endsWith("Pr")) return "";
        var text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
            if (child.getNodeType() == Node.ELEMENT_NODE) text.append(math(child));
        return text.toString();
    }

    private static String part(Node node, String name) {
        Node part = child(node, MATH, name);
        return part == null ? "" : math(part);
    }

    private static String group(String text) {
        return text.matches("[\\p{L}\\p{N}.]*") ? text : "(" + text + ")";
    }

    private static String delimiter(Node properties, String name, String fallback) {
        Node element = properties == null ? null : child(properties, MATH, name);
        if (element == null) return fallback;
        var value = element.getAttributes().getNamedItemNS(MATH, "val");
        return value == null ? "" : value.getNodeValue();
    }

    // XmlBeans DOM nodes do not implement getTextContent.
    private static String text(Node node) {
        var text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                text.append(child.getNodeValue());
        return text.toString();
    }

    private static Node child(Node node, String namespace, String name) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
            if (is(child, namespace, name)) return child;
        return null;
    }

    private static boolean is(Node node, String namespace, String name) {
        return node.getNodeType() == Node.ELEMENT_NODE && namespace.equals(node.getNamespaceURI())
                && name.equals(node.getLocalName());
    }
}
