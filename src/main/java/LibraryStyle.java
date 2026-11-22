import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class LibraryStyle {
    static final Color CANVAS = new Color(0xF8F6ED), INK = new Color(0x25251F),
            MUTED = new Color(0x706E62), ACCENT = new Color(0xB74323), LINE = new Color(0xD9D6C8),
            SOFT_LINE = new Color(0xECE9DE), SELECTED = new Color(0xF3EEE1), SECONDARY = new Color(0x4A4940),
            STAGE = new Color(0xF1F0EB);
    /** Underlined tabs: the selected tab keeps dark text while hovered, unlike filled card tabs. */
    static final String TABS = "tabType: underlined; background: #ffffff; tabHeight: 36; tabSelectionHeight: 2; underlineColor: #b74323;"
            + " inactiveUnderlineColor: #b74323; selectedForeground: #25251f; foreground: #4a4940;"
            + " hoverColor: #f3f1ea; focusColor: #ffffff00; contentAreaColor: #e4e0d3;"
            + " showTabSeparators: false; tabInsets: 4,12,4,12";
    static final Font TYPE = new Font("Malgun Gothic", Font.PLAIN, 14);

    static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(TYPE.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float)size));
        label.setForeground(color);
        return label;
    }

    static JPanel panel(LayoutManager layout, int padding) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(padding,padding,padding,padding));
        return panel;
    }

    static JPanel card(LayoutManager layout) {
        JPanel p = panel(layout, 16);
        p.setBackground(Color.WHITE);
        p.setOpaque(true);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                new EmptyBorder(16,16,16,16)));
        return p;
    }

    /** One button shape for the whole app: 8 px corners, accent fill only for the main action. */
    static void button(AbstractButton button, boolean primary) {
        button.putClientProperty("JButton.buttonType", null);
        button.setMargin(new Insets(5,12,5,12));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(TYPE.deriveFont(primary ? Font.BOLD : Font.PLAIN, 13f));
        button.putClientProperty("FlatLaf.style", primary
                ? "arc: 8; borderWidth: 0; focusWidth: 0; background: #b74323; foreground: #ffffff;"
                + " hoverBackground: #9e391d; pressedBackground: #8f321a; disabledBackground: #e7d3cb"
                : "arc: 8; focusWidth: 0; borderColor: #d6d2c4; hoverBorderColor: #b5b2a6; background: #ffffff;"
                + " hoverBackground: #faf8f1; foreground: #25251f");
    }

    /** Low-emphasis text button for status-bar and inline links. */
    static void link(AbstractButton button) {
        button.putClientProperty("JButton.buttonType", "borderless");
        button.setFont(TYPE.deriveFont(12f));
        button.setForeground(SECONDARY);
        button.setMargin(new Insets(2,6,2,6));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    static Icon document(String type, int size, Color color) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D)graphics.create();
                g.translate(x,y); g.scale(size/48d,size/48d);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),28));
                g.fill(new RoundRectangle2D.Float(0,0,48,48,13,13));
                g.setColor(color); g.fillRoundRect(13,8,23,32,4,4);
                g.setColor(Color.WHITE);
                if (type.isEmpty()) {
                    for (int i=0;i<3;i++) g.fillRoundRect(18,17+i*5,i==2?8:13,2,2,2);
                } else {
                    g.setFont(new Font("Dialog",Font.BOLD,17));
                    g.drawString(type,18,31);
                }
                g.dispose();
            }
        };
    }

    /** Two overlapping sheets, the usual "copy" sign. */
    static Icon copyIcon() {
        return new Icon() {
            public int getIconWidth() { return 14; }
            public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c != null && !c.isEnabled() ? LINE : MUTED);
                g.setStroke(new BasicStroke(1.4f));
                g.drawRoundRect(x + 5, y + 5, 8, 8, 3, 3);
                g.drawPolyline(new int[]{x + 3, x + 1, x + 1, x + 9, x + 9}, new int[]{y + 9, y + 9, y + 1, y + 1, y + 3}, 5);
                g.dispose();
            }
        };
    }

    static Icon fileIcon(Library.Hit hit, int size) {
        String name = hit.source().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "xls", "xlsx" -> document("X", size, new Color(0x24765D));
            case "doc", "docx" -> document("W", size, new Color(0x315B99));
            case "pdf" -> document("F", size, new Color(0xA22D35));
            default -> document("P", size, new Color(0xB95331));
        };
    }

    /** Fixed thumbnail slot so titles line up whatever the picture's shape. */
    private static Icon slot(Icon picture, int width, int height) {
        return new Icon() {
            public int getIconWidth() { return width; }
            public int getIconHeight() { return height; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setColor(STAGE);
                g.fillRoundRect(x, y, width, height, 6, 6);
                if (picture instanceof ImageIcon image && image.getIconWidth() > 0 && image.getIconHeight() > 0) {
                    double scale = Math.min((width - 4d) / image.getIconWidth(), (height - 4d) / image.getIconHeight());
                    int w = (int) Math.round(image.getIconWidth() * scale), h = (int) Math.round(image.getIconHeight() * scale);
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(image.getImage(), x + (width - w) / 2, y + (height - h) / 2, w, h, null);
                } else if (picture != null) {
                    picture.paintIcon(c, g, x + (width - picture.getIconWidth()) / 2, y + (height - picture.getIconHeight()) / 2);
                }
                g.dispose();
            }
        };
    }

    /** Fits text into at most {@code lines} lines of {@code width} pixels, breaking anywhere and ending with "…". */
    static java.util.List<String> fit(String text, FontMetrics metrics, int width, int lines) {
        String rest = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        var result = new java.util.ArrayList<String>();
        while (!rest.isEmpty() && result.size() < lines) {
            boolean last = result.size() == lines - 1;
            if (metrics.stringWidth(rest) <= width) { result.add(rest); break; }
            int end = 1;
            String suffix = last ? "…" : "";
            while (end < rest.length() && metrics.stringWidth(rest.substring(0, end + 1) + suffix) <= width) end++;
            if (!last) {
                // Prefer breaking after a separator so file names split at "_", "." or a space.
                for (int i = end - 1; i > end / 2; i--) {
                    if (" _-.)]".indexOf(rest.charAt(i)) >= 0) { end = i + 1; break; }
                }
            }
            result.add(rest.substring(0, end).stripTrailing() + suffix);
            rest = rest.substring(end).stripLeading();
        }
        return result;
    }

    /** Escapes document text before it enters a Swing HTML label. */
    static String escape(String text) {
        return org.jsoup.nodes.Entities.escape(text == null ? "" : text);
    }

    /**
     * Light result row. The title wraps to two lines so versions that differ only at the end of a
     * long file name stay distinguishable, and markdown markers never reach the excerpt.
     */
    private static JLabel rowLabel(String text, Font font, Color color) {
        var label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    static ListCellRenderer<Library.Hit> renderer(java.util.function.Function<Library.Hit,Icon> thumbnails) {
        return (list, hit, index, selected, focus) -> {
            JPanel row = panel(new BorderLayout(10,0), 0);
            row.setOpaque(true);
            row.setBackground(selected ? SELECTED : Color.WHITE);
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0,0,1,0,SOFT_LINE), new EmptyBorder(8,10,8,10)));
            Icon thumbnail = thumbnails.apply(hit);
            Icon icon = hit.picture() != null ? slot(thumbnail, 64, 44) : thumbnail != null ? slot(thumbnail, 40, 40) : fileIcon(hit, 40);
            var picture = new JLabel(icon);
            picture.setVerticalAlignment(SwingConstants.TOP);
            row.add(picture, BorderLayout.WEST);
            int width = Math.max(80, list.getWidth() - icon.getIconWidth() - 32);
            String detail = hit.picture() == null ? hit.section() : hit.picture().location();
            String excerpt = SearchStudio.readable(hit.displayExcerpt());
            if (excerpt.equals(detail) || detail.endsWith(excerpt)) excerpt = "";
            var text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            Font titleFont = TYPE.deriveFont(Font.BOLD, 13f), small = TYPE.deriveFont(12f);
            for (String line : fit(hit.title(), list.getFontMetrics(titleFont), width, 2))
                text.add(rowLabel(line, titleFont, INK));
            text.add(rowLabel(fit(detail, list.getFontMetrics(small), width, 1).stream().findFirst().orElse(" "), small, MUTED));
            if (!excerpt.isEmpty())
                text.add(rowLabel(fit(excerpt, list.getFontMetrics(small), width, 1).getFirst(), small, SECONDARY));
            row.add(text);
            row.setToolTipText(hit.title());
            return row;
        };
    }
}
